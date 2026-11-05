package common.security;

/**
 * 从 JWT 解析并回填后，挂在 SecurityContext 里的当前登录用户信息。
 */

/*
*  record：它是 Java 16 之后的一种「快速定义不可变数据类」的语法。上面这一行，等价于自动帮你生成了：
- 三个私有 final 字段：userId、role、fleetId；
- 一个带这三个参数的构造方法；
- 三个取值方法：userId()、role()、fleetId()（注意不是 getUserId()，是 userId()）；
- equals、hashCode、toString。
也就是说，LoginUser 就是一个「只用来装当前登录用户信息」的容器，装完就不变了。*/
public record LoginUser(Long userId, String role, Long fleetId) {//role存的是该角色是管理员还是司机
}
