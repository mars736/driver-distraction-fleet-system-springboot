package common.security;

import common.exception.BusinessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 当前登录用户工具：在 Controller / Service 里拿当前用户。
 */

/*
* Spring Security 会把「当前登录的人」放在一个叫 SecurityContext 的对象里，而这个 SecurityContext 是跟当前请求的线程绑定的（底层是 ThreadLocal）。
* 意思就是：同一个请求里，不管你在 Controller 还是 Service，都能拿到同一个「当前用户」，请求结束后它自动清掉，不会串到别人的请求里。
SecurityContextHolder.getContext() 就是把这个「当前请求的安全上下文」取出来。*/
    /*这个类就是从 SecurityContextHolder 里读出当前用户，并处理没登录的情况，发现没东西就喊一声*/
public class UserContext {

    public static LoginUser get() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        //Authentication 对象。这个对象代表「一次认证结果」，里面装着当前用户是谁、有什么权限。
        //如果这个请求没经过 JWT 校验，或者根本没登录，这里可能是 null，也可能是一个「匿名认证」对象。
        if (authentication != null && authentication.getPrincipal() instanceof LoginUser loginUser) {
            //getPrincipal()：Authentication 里的「主体」，就是当前用户对象。JWT 校验通过后，
            // 塞进去的 principal 就是 LoginUser
            //instanceof LoginUser loginUser 是 Java 16 的「模式匹配 instanceof」：如果 getPrincipal() 真的是一个 LoginUser，
            // 就顺便把它转成 LoginUser 类型，并用变量 loginUser 接住。
            //至于为什么之前我之前传进去是LoginUser，结果它给我返回的东西返祖了
            //是因为public UsernamePasswordAuthenticationToken(Object principal, Object credentials, ...)构造方法里面接住我的LoginUser对象的就是Object类型
            return loginUser;
        }
        throw new BusinessException(401, "未登录");
    }
}
