package common.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具：签发和解析 token。
 *
 * token 里只放两样东西：
 *   - subject：userId（主键）
 *   - tokenVersion：账号禁用 / 改密时会 +1，用来让旧 token 立即失效
 */

/*
* JWT（JSON Web Token）就是一张数字通行证：登录成功后，服务器把「你是谁」写进一段文本，再用一把只有服务器知道的密钥给它盖个「防伪章」，然后把这串文本发给前端。
* 前端之后每次请求都带着它，服务器收到后先用同一把密钥验章——章对了，才相信里面写的「你是谁」。这串文本长这样：头部.载荷.签名，三段用点号隔开。*/
@Component
public class JwtUtil {

    private final SecretKey key;//加密/验证签名用的密钥对象
    private final long expireMs;//token 的有效期，单位是毫秒

    public JwtUtil(@Value("${jwt.secret}") String secret,//@Value("${jwt.secret}")：从 application.yml 里读 jwt.secret 的值，
                   @Value("${jwt.expire-ms:7200000}") long expireMs) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));//secret.getBytes把jwt密钥字符串转换为字节数组，因为加密算法只接受字节格式的
        //
        this.expireMs = expireMs;
    }

    public String generateToken(Long userId, Integer tokenVersion) {//登录成功时调用这个方法，签发出 token。userId：用户主键 id，知道「是谁」
        Date now = new Date();//现在这一刻
        return Jwts.builder()//这一行自动生成jwt头部，下面的直到.expiration都是载荷里的内容
                .subject(String.valueOf(userId))//sub 字段，主题（这里是 userId） 把 Long 类型的 userId 转成字符串，因为 subject 要求是字符串。
                .claim("tokenVersion", tokenVersion)//自定义私有载荷字段
                .issuedAt(now)// 设置载荷的「签发时间」字段 iat
                .expiration(new Date(now.getTime() + expireMs))//设置载荷的exp 字段，过期时间 now.getTime()：把 now 转成毫秒数（从 1970 年 1 月 1 日到现在的毫秒数）。
                .signWith(key)//用密钥 key 签名，生成 token 的签名。
                .compact();
    }

    public Claims parseToken(String token) {
        return Jwts.parser()//解析器构建器
                .verifyWith(key)//把签名密钥给解析器
                .build()//解析器构建完成
                .parseSignedClaims(token)//把前端传回来的token扔进去解析
                /*
                * 用解析器去解析传入的 token。这一步是真正干活的地方，它会同时做三件事：
                1. 检查格式对不对；
                2. 用 key 验证签名对不对；
                3. 检查有没有过期。
                任何一项不通过，都会抛异常。常见的有：
                    - 签名不对 → SignatureException（token 被改过或不是这把 key 签的）；
                    - 已过期 → ExpiredJwtException；
                    - 格式不对 → MalformedJwtException。*/
                .getPayload();  //返回类型是 Claims
    }

    public long getExpireMs() {
        return expireMs;
    }
}
