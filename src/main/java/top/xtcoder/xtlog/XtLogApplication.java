package top.xtcoder.xtlog;

import top.xtcoder.xtboot.XtApplication;
import top.xtcoder.xtboot.XtBootApplication;

/**
 * xtlog-server 启动入口。
 * 框架自动扫描本类所在包（top.xtcoder.xtlog）及其子包下的 @Component/@Service/@Controller/@RestController。
 */
@XtBootApplication
public class XtLogApplication {

    public static void main(String[] args) {
        XtApplication.run(XtLogApplication.class, args);
    }
}
