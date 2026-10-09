package top.xtcoder.xtlog.util;

import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * 本机 IP 解析：在「所有已启用、非回环的 IPv4 网卡地址」里挑一个**最像真实局域网网卡**的。
 *
 * 用途：文件采集（FILE）与命令采集（CMD）的日志物理上就落在本机，
 * 采集时给每条日志打上本机 IP，界面上即可一眼区分「这条日志来自哪台机器」。
 * HTTP 采集不走这里——那类日志由微服务上报端自己带 srcIp（见 IngestController）。
 *
 * <p>为什么不能简单「取第一个」：机器上往往有一堆虚拟网卡，且枚举顺序里它们常常排在真实网卡前面，
 * 例如 WSL2 / Hyper-V 的虚拟交换机（vEthernet (WSL) → 172.27.0.1）、
 * Docker 网桥（docker0 → 172.17.0.1）、VMware/VirtualBox/Tailscale/VPN 等。
 * 直接取第一个会拿到这些地址，而不是真正的局域网 IP。
 *
 * <p>解析策略（按优先级）：
 *  1. 手动指定：系统属性 {@code -Dxtlog.host.ip=} 或环境变量 {@code XTLOG_HOST_IP}
 *     （容器 / 多网卡 / 判断不准时的兜底手段，永远最优先）；
 *  2. 枚举网卡并对每个 IPv4 候选打分，取最高分：
 *     <ul>
 *       <li>网段：192.168.* > 10.* > 172.16~31.*（Docker/WSL 常用段，压低） > 其他/公网；
 *           169.254.*（链路本地）与 127.* 直接丢弃</li>
 *       <li>网卡：有 MAC 地址 +20（物理网卡特征）；名称命中虚拟关键字 -60；
 *           isVirtual() -40；点对点(VPN) -30</li>
 *       <li>若该地址正是「默认出网路由」选中的源地址（UDP connect 探测，不发数据包）+50</li>
 *     </ul>
 *  3. 一个候选都没有时兜底 127.0.0.1（本机采集，回环也能表达「就是这台机器」）。
 *
 * 只取 IPv4：IPv6 在日志表里辨识度低，且 src_ip 列按 45 字符设计已足够。
 */
public final class HostAddresses {

    private static volatile String cached;

    private HostAddresses() {
    }

    /** 本机 IPv4（缓存，只探测一次） */
    public static String localIp() {
        String c = cached;
        if (c != null) {
            return c;
        }
        synchronized (HostAddresses.class) {
            if (cached != null) {
                return cached;
            }
            cached = resolve();
            return cached;
        }
    }

    /**
     * 所有候选（IP + 网卡名 + 得分），仅用于排查：当自动选中的 IP 不对时，
     * 把它打到日志里，用户一眼能看到机器上有哪些地址、各自为什么被选中。
     */
    public static List<String> describeCandidates() {
        List<Candidate> list = collect();
        List<String> out = new ArrayList<>();
        for (Candidate c : list) {
            out.add(c.ip + " (" + c.nif + ") score=" + c.score);
        }
        return out;
    }

    // ------------------------------------------------------------------ 解析

    private static String resolve() {
        // 1) 手动指定最优先
        String forced = firstNonBlank(System.getProperty("xtlog.host.ip"), System.getenv("XTLOG_HOST_IP"));
        if (forced != null) {
            // 日志用 ASCII：Windows 控制台默认 GBK，中文输出会乱码
            System.out.println("[xtlog] host ip (specified manually): " + forced);
            return forced;
        }

        List<Candidate> list = collect();
        if (list.isEmpty()) {
            System.out.println("[xtlog] no non-loopback IPv4 NIC found, host ip fallback 127.0.0.1");
            return "127.0.0.1";
        }

        Candidate best = list.get(0);
        String chosen = best.ip;
        if (list.size() > 1) {
            // 有多个候选时才打印，便于排查「为什么选了它」（含各候选得分）
            System.out.println("[xtlog] host ip: " + chosen + " (" + best.nif + ")"
                    + ", candidates " + describeCandidates());
        }
        return chosen;
    }

    private static List<Candidate> collect() {
        String outbound = routeProbe();
        List<Candidate> list = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> nifs = NetworkInterface.getNetworkInterfaces();
            while (nifs != null && nifs.hasMoreElements()) {
                NetworkInterface nif = nifs.nextElement();
                try {
                    if (!nif.isUp() || nif.isLoopback()) {
                        continue;
                    }
                } catch (Exception ignore) {
                    continue;
                }
                String nifName = nif.getName() + " / " + nif.getDisplayName();
                Enumeration<InetAddress> addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress() || a.isLinkLocalAddress()) {
                        continue;
                    }
                    String ip = a.getHostAddress();
                    int score = scoreOf(ip, nif, nifName);
                    if (score == Integer.MIN_VALUE) {
                        continue; // 丢弃（回环 / 链路本地 / 非法）
                    }
                    if (ip.equals(outbound)) {
                        score += 50; // 默认出网路由选中的源地址，最可能是「真正的本机 IP」
                    }
                    list.add(new Candidate(ip, nif.getName(), score));
                }
            }
        } catch (Exception ignore) {
            // 枚举失败：返回已有的（可能为空），由调用方兜底
        }
        Collections.sort(list);
        return list;
    }

    private static int scoreOf(String ip, NetworkInterface nif, String nifName) {
        byte[] b = ipToBytes(ip);
        if (b == null || b[0] == 127 || (b[0] == 169 && b[1] == 254)) {
            return Integer.MIN_VALUE;
        }
        int s;
        if (b[0] == 192 && b[1] == 168) {
            s = 100; // 家庭 / 办公局域网最常见网段
        } else if (b[0] == 10) {
            s = 90;
        } else if (b[0] == 172 && b[1] >= 16 && b[1] <= 31) {
            s = 50; // Docker 网桥 / WSL 虚拟交换机 / 部分企业内网都在这段，压低优先级
        } else {
            s = 40; // 公网地址或其他
        }

        String lower = nifName == null ? "" : nifName.toLowerCase();
        try {
            byte[] mac = nif.getHardwareAddress();
            if (mac != null && mac.length > 0) {
                s += 20; // 有 MAC 地址更像物理网卡（虚拟网卡也常伪造 MAC，故仍需配合名称过滤）
            }
        } catch (Exception ignore) {
            // 某些平台取 MAC 会抛异常，忽略即可
        }
        if (nif.isVirtual()) {
            s -= 40;
        }
        try {
            if (nif.isPointToPoint()) {
                s -= 30; // 多是 VPN / 拨号
            }
        } catch (Exception ignore) {
            // 部分平台不支持该查询
        }
        if (matchesVirtualKeyword(lower)) {
            s -= 60;
        }
        return s;
    }

    private static final String[] VIRTUAL_KEYWORDS = {
            // 容器
            "docker", "veth", "br-", "bridge", "lxc", "container", "kube", "cni", "flannel", "calico",
            // 虚拟机 / 子系统
            "vmware", "virtualbox", "vbox", "hyper-v", "hyperv", "wsl", "vethernet", "default switch",
            "virtual", "pseudo", "parallels", "qemu", "kvm",
            // 隧道 / VPN / 覆盖网络
            "tap", "tun", "ppp", "openvpn", "wireguard", "tailscale", "zerotier", "hamachi", "radmin",
            "sangfor", "easyconnect", "vpn", "isatap", "teredo", "6to4",
            // 其他
            "loopback", "miniport", "bluetooth", "npcap", "npf"
    };

    private static boolean matchesVirtualKeyword(String lowerName) {
        for (String k : VIRTUAL_KEYWORDS) {
            if (lowerName.contains(k)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 出网路由探测：对一个公网地址建 UDP "连接"（只查路由表，不真正发包），
     * 拿到内核为该路由选择的源 IP —— 通常就是接外网的那个真实网卡地址。
     */
    private static String routeProbe() {
        try (DatagramSocket s = new DatagramSocket()) {
            s.connect(InetAddress.getByName("223.5.5.5"), 80); // 阿里公共 DNS，仅用于查路由
            InetAddress la = s.getLocalAddress();
            if (la instanceof Inet4Address && !la.isLoopbackAddress() && !la.isLinkLocalAddress()) {
                return la.getHostAddress();
            }
        } catch (Exception ignore) {
            // 无外网 / 无默认路由时忽略，退化到纯打分
        }
        return null;
    }

    private static byte[] ipToBytes(String ip) {
        if (ip == null) {
            return null;
        }
        String[] p = ip.split("\\.");
        if (p.length != 4) {
            return null;
        }
        byte[] b = new byte[4];
        try {
            for (int i = 0; i < 4; i++) {
                int v = Integer.parseInt(p[i]);
                if (v < 0 || v > 255) {
                    return null;
                }
                b[i] = (byte) v;
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return b;
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) {
            return null;
        }
        for (String v : vals) {
            if (v != null && !v.trim().isEmpty()) {
                return v.trim();
            }
        }
        return null;
    }

    /** 一个候选地址，按得分降序 */
    private static final class Candidate implements Comparable<Candidate> {
        final String ip;
        final String nif;
        final int score;

        Candidate(String ip, String nif, int score) {
            this.ip = ip;
            this.nif = nif;
            this.score = score;
        }

        @Override
        public int compareTo(Candidate o) {
            return Integer.compare(o.score, this.score); // 高分在前
        }
    }
}
