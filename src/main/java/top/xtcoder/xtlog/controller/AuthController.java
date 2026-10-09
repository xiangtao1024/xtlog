package top.xtcoder.xtlog.controller;

import top.xtcoder.xtboot.auth.StpUtil;
import top.xtcoder.xtboot.auth.annotation.SaCheckLogin;
import top.xtcoder.xtboot.context.annotation.Autowired;
import top.xtcoder.xtboot.core.Dict;
import top.xtcoder.xtboot.web.annotation.GetMapping;
import top.xtcoder.xtboot.web.annotation.PostMapping;
import top.xtcoder.xtboot.web.annotation.PutMapping;
import top.xtcoder.xtboot.web.annotation.RequestBody;
import top.xtcoder.xtboot.web.annotation.RestController;
import top.xtcoder.xtboot.web.model.ApiResponse;

import top.xtcoder.xtlog.service.UserService;

/**
 * 登录 / 注销 / 个人资料。
 *
 * <p>会话用的是框架自带的 StpUtil（内存会话 + Token，默认名字 satoken，30 天有效）。
 * Token 支持「请求头 → Cookie → 查询参数」三种携带方式，SSE 这种不能自定义请求头的场景
 * 可以用 {@code ?satoken=xxx} 直连。</p>
 *
 * <p>注意：默认 admin 账号旨在本地单机部署，会话在进程重启后会失效（内存存储）。</p>
 */
@RestController
public class AuthController {

    @Autowired
    private UserService userService;

    @PostMapping("/api/auth/login")
    public Object login(@RequestBody Dict body) {
        String username = (String) body.get("username");
        String password = (String) body.get("password");
        Dict user = userService.login(username, password);
        if (user == null) {
            return ApiResponse.error(401, "账号或密码不正确");
        }
        String token = StpUtil.login(user.get("id"));

        Dict data = new Dict();
        data.put("token", token);
        data.put("user", user);
        return ApiResponse.ok(data);
    }

    @PostMapping("/api/auth/logout")
    public Object logout() {
        try {
            StpUtil.logout();
        } catch (Exception e) {
            // 本来就没登录，也算注销成功
        }
        Dict d = new Dict();
        d.put("ok", Boolean.TRUE);
        return ApiResponse.ok(d);
    }

    @SaCheckLogin
    @GetMapping("/api/auth/profile")
    public Object profile() {
        String id = StpUtil.getLoginIdAsString();
        Dict user = userService.findById(Long.parseLong(id));
        if (user == null) {
            return ApiResponse.error(404, "用户不存在");
        }
        return ApiResponse.ok(UserService.safe(user));
    }

    @SaCheckLogin
    @PutMapping("/api/auth/profile")
    public Object updateProfile(@RequestBody Dict body) {
        String id = StpUtil.getLoginIdAsString();
        long uid = Long.parseLong(id);

        Object oldPwd = body.get("oldPassword");
        Object newPwd = body.get("newPassword");
        if (newPwd != null && String.valueOf(newPwd).length() > 0) {
            if (String.valueOf(newPwd).length() < 6) {
                return ApiResponse.error(400, "新密码至少 6 位");
            }
            if (!userService.changePassword(uid, String.valueOf(oldPwd), String.valueOf(newPwd))) {
                return ApiResponse.error(400, "旧密码不正确");
            }
        }
        Dict patch = new Dict();
        if (body.get("nickname") != null) {
            patch.put("nickname", body.get("nickname"));
        }
        if (body.get("email") != null) {
            patch.put("email", body.get("email"));
        }
        if (!patch.isEmpty()) {
            userService.updateProfile(uid, patch);
        }
        Dict user = userService.findById(uid);
        return ApiResponse.ok(UserService.safe(user));
    }
}
