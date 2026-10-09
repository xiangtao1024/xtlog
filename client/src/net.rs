use std::io::{Read, Write};
use std::net::{TcpStream, ToSocketAddrs};
use std::time::Duration;

/**
 * 极简 HTTP 客户端（只用标准库，不引入 reqwest 这类带 TLS 栈的重依赖）。
 *
 * 因此只支持 http:// —— 若 xtlog 走 https，请在前面用 nginx  terminate TLS
 * 后以 http 转发到 18080，客户端填 http://127.0.0.1:18080 即可。
 * 直接填 https:// 会明确报错，避免静默失败。
 *
 * 每次请求独立建连（Connection: close）：日志推送频率不高，
 * 省掉连接复用/长连接状态管理的复杂度，也更利于服务端异常时快速失败重试。
 */
pub fn post(url: &str, body: &str, timeout_ms: u64) -> Result<(u16, String), String> {
    if url.trim().to_lowercase().starts_with("https://") {
        return Err(
            "暂不支持 https：客户端为标准库实现、不带 TLS 栈。\
             请在 xtlog 前挂 nginx 做 TLS 终结，客户端配置 http:// 地址"
                .to_string(),
        );
    }
    let (host, port, path) = split_url(url)?;
    let timeout = Duration::from_millis(timeout_ms);

    let sock_addr = (host.as_str(), port)
        .to_socket_addrs()
        .map_err(|e| format!("解析服务地址失败 {}:{} -> {}", host, port, e))?
        .next()
        .ok_or_else(|| format!("服务地址无法解析：{}:{}", host, port))?;

    let mut stream = TcpStream::connect_timeout(&sock_addr, timeout)
        .map_err(|e| format!("连接 {}:{} 失败：{}", host, port, e))?;
    stream
        .set_write_timeout(Some(timeout))
        .and_then(|_| stream.set_read_timeout(Some(timeout)))
        .map_err(|e| format!("设置超时失败：{}", e))?;

    let req = format!(
        "POST {} HTTP/1.1\r\n\
         Host: {}:{}\r\n\
         Content-Type: application/json; charset=UTF-8\r\n\
         Content-Length: {}\r\n\
         Connection: close\r\n\
         \r\n",
        path,
        host,
        port,
        body.as_bytes().len()
    );
    stream
        .write_all(req.as_bytes())
        .and_then(|_| stream.write_all(body.as_bytes()))
        .and_then(|_| stream.flush())
        .map_err(|e| format!("发送请求失败：{}", e))?;

    let mut resp = Vec::new();
    stream
        .read_to_end(&mut resp)
        .map_err(|e| format!("读取响应失败：{}", e))?;
    drop(stream);

    let text = String::from_utf8_lossy(&resp).to_string();
    let status = text
        .split_whitespace()
        .nth(1)
        .and_then(|s| s.parse::<u16>().ok())
        .unwrap_or(0);
    let body_out = match text.find("\r\n\r\n") {
        Some(i) => text[i + 4..].to_string(),
        None => String::new(),
    };
    Ok((status, body_out))
}

/// url -> (host, port, path)；path 至少为 "/"
pub fn split_url(url: &str) -> Result<(String, u16, String), String> {
    let rest = match url.split_once("://") {
        Some((scheme, r)) => {
            if !scheme.eq_ignore_ascii_case("http") {
                return Err(format!("不支持的协议：{}://（只支持 http）", scheme));
            }
            r
        }
        None => url,
    };
    let (authority, path) = match rest.find('/') {
        Some(i) => (&rest[..i], &rest[i..]),
        None => (rest, "/"),
    };
    if authority.is_empty() {
        return Err(format!("服务地址缺少主机名：{}", url));
    }
    let (host, port) = match authority.rfind(':') {
        Some(i) => {
            let p: u16 = authority[i + 1..]
                .parse()
                .map_err(|_| format!("端口不是数字：{}", &authority[i + 1..]))?;
            (&authority[..i], p)
        }
        None => (authority, 80u16),
    };
    let path = if path.is_empty() { "/" } else { path };
    Ok((host.to_string(), port, path.to_string()))
}
