package common.security;

import common.util.JwtUtil;
import entity.User;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import mapper.UserMapper;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT 校验过滤器：每个请求进来，先解析 Authorization: Bearer xxx，
 * 再查库确认「账号未被禁用」且「tokenVersion 没变」，两者都通过才放行。
 */

/*
* JwtAuthFilter 自己不会自动生效，是在 common.config.SecurityConfig 里通过 addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
* 挂到过滤器链上的，位置比较靠前，所以在进入 Controller 之前就会跑。
*
* */
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {//OncePerRequestFilter 是 Spring 提供的一个过滤器基类，保证「一个请求只执行一次过滤逻辑」
    // （有的请求会被转发，普通 Filter 可能跑多次，这个不会）。继承它之后，你只需要实现 doFilterInternal 这个方法。

    private final JwtUtil jwtUtil;
    private final UserMapper userMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        /*
        * request：请求对象，能拿到请求头、参数等。
          response：响应对象，能往里面写返回内容。
          chain：过滤器链。调用 chain.doFilter(...) 表示「我检查完了，交给下一个过滤器/Controller」。*/
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            chain.doFilter(request, response);//没有 token 时，直接 chain.doFilter(request, response)：放行，交给后面的过滤器。
            // 这里注意：放行不代表一定能访问成功——后面还有 Spring Security 的权限规则（在 SecurityConfig 里写的哪些路径公开、哪些要登录）。
            // 公开接口会继续正常执行；需要登录的接口会在后面被拦成 401。我们这里只是「不在这里拦它」。
            return;
        }

        String token = header.substring(7);//"Bearer " 正好是 7 个字符（B-e-a-r-e-r-空格），所以 substring(7) 就是把前面这 7 个字符去掉，剩下纯 token 字符串。
        try {
            Claims claims = jwtUtil.parseToken(token);//先拿到载荷，注意这里token被篡改或token格式错误或token载荷里设置的时间超时，都会抛异常
            Long userId = Long.valueOf(claims.getSubject());//Subject里当初组装jwt的时候装的是userid
            Integer tokenVersion = claims.get("tokenVersion", Integer.class);

            User user = userMapper.selectById(userId);
            if (user == null
                    || !Boolean.TRUE.equals(user.getEnabled())
                    || !user.getTokenVersion().equals(tokenVersion)) {
                writeUnauthorized(response, "token 已失效");
                return;
            }//这个if在验证拿到的token跟数据库里的user是否一致，看看过期了没

            LoginUser loginUser = new LoginUser(user.getId(), user.getRole(), user.getFleetId());//校验通过了，把数据库里的用户信息装成一个 LoginUser 对象
            List<GrantedAuthority> authorities =//这是权限对象列表
                    List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole()));//(new SimpleGrantedAuthority()创建一个权限对象,权限名就是ROLE_ + user.getRole()，比如ROLE_DRIVER。GrantedAuthority是一个接口，里面只有getAuthority()需要重写，SimpleGrantedAuthority就是它的实现类，可以直接get我刚刚设置好的权限名。用List是因为一个角色可能有多个权限，当然这个项目只有一个
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(loginUser, null, authorities);//创建 Spring Security 的「认证对象」，表示「这个人已认证，并拥有这些权限。      一个loginuser为什么权限对象是一个列表：因为一个角色可能有多个权限
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));//往认证对象里附加请求细节（比如来源 IP、session id）。这步不是必须的，属于「顺手补全信息」，后面如果需要记录登录 IP 会用到。
            SecurityContextHolder.getContext().setAuthentication(authentication);//把认证对象放进当前请求线程的「安全上下文」
            /*SecurityContextHolder 底层是基于 ThreadLocal 的：同一个请求线程里，任何地方都能读到它；请求结束自动清理，不会串到别人的请求。
            放进去之后，Controller/Service 里调用 UserContext.get() 就能拿到当前用户了。*/
        } catch (Exception e) {
            writeUnauthorized(response, "token 无效或已过期");
            return;
        }

        chain.doFilter(request, response);
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"message\":\"" + message + "\",\"data\":null}");
        /*response.getWriter().write("...") — 把 JSON 字符串写入响应缓冲区
          然后 return，没有调用 chain.doFilter(request, response)的话。整条链子就结束了。这恰恰是关键——Tomcat 发现过滤器链没有继续往下走（没到 Controller），
          就会自动把缓冲区里的内容 commit 并 flush 回客户端。*/
        /*
        * 控制权的"来"和"回"

              → → → 请求方向 → → →
    Filter1 ──→ Filter2 ──→ Controller
    Filter1 ←── Filter2 ←── Controller
              ← ← ← 响应方向 ← ← ←
    这就是经典的责任链模式。chain.doFilter() 调用时往下走，调用返回时往回走。等最外层的 Filter 的 doFilter() 方法也结束了，Tomcat 就知道："整个链子跑完了"。

    然后 Tomcat 做收尾工作：把 response 缓冲区里的数据 flush 出去。
    *
    *
    *
    * 不管你是怎么结束的（正常走完、中途 return、甚至抛异常），只要最外层的 Filter 方法返回了，Tomcat 就认为这个请求处理完毕。然后用当前的response做收尾：*/
    }
}
