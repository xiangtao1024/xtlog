package top.xtcoder.xtlog.config;

import java.util.Arrays;
import java.util.List;

import top.xtcoder.xtboot.auth.AuthProvider;
import top.xtcoder.xtboot.auth.StpUtil;
import top.xtcoder.xtboot.auth.AuthInterceptor;
import top.xtcoder.xtboot.context.annotation.Autowired;
import top.xtcoder.xtboot.context.annotation.Component;
import top.xtcoder.xtboot.web.config.WebConfigurer;
import top.xtcoder.xtboot.web.interceptor.InterceptorRegistry;

import top.xtcoder.xtlog.service.UserService;

/**
 * 鉴权装配。
 *
 * <p>注册框架自带的 {@link AuthInterceptor} 后，标了 {@code @SaCheckLogin} 的接口才需要登录；
 * 未标注的一律放行 —— 所以日志上报（/api/logs 的 POST，用应用 token 而非会话）不受影响。</p>
 *
 * <p>注意：拦截器只对「匹配到 Controller 方法」的请求生效，静态资源不会命中，
 * 因此 index.html / assets 始终可直接访问。</p>
 */
@Component
public class AuthSetup implements WebConfigurer {

    @Autowired
    private UserService userService;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 角色/权限数据源：本项目只有「管理员」一种角色，够用即可
        StpUtil.setAuthProvider(new AuthProvider() {
            @Override
            public List<String> getRoleList(Object loginId) {
                return Arrays.asList("admin");
            }

            @Override
            public List<String> getPermissionList(Object loginId) {
                return Arrays.asList("app:*", "category:*", "log:*", "setup:*");
            }
        });

        // 用户表 + 默认管理员就绪（幂等）
        userService.ensureTableAndAdmin();

        registry.addInterceptor(new AuthInterceptor())
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/login", "/api/setup/status");
    }
}
