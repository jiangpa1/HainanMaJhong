package hainanMahjong.controller;

import hainanMahjong.common.Result;
import hainanMahjong.dto.LoginDTO;
import hainanMahjong.dto.RefreshDTO;
import hainanMahjong.dto.RegisterDTO;
import hainanMahjong.dto.UpdateNicknameDTO;
import hainanMahjong.dto.UpdatePasswordDTO;
import hainanMahjong.service.TokenService;
import hainanMahjong.service.RoomService;
import hainanMahjong.service.UserService;
import hainanMahjong.vo.TokenPair;
import hainanMahjong.vo.UserVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;

/**
 * 登录 / 注册 / 个人信息（昵称、密码）接口。
 *
 * <p><b>身份来源</b>：除 {@code register} / {@code login} / {@code refresh} 外，
 * 所有接口的"我是谁"都来自 {@code @RequestAttribute("userId")}
 * —— 由 {@link hainanMahjong.interceptor.JwtInterceptor} 从 token 解析后挂上。
 * <b>不再由调用方传 userId</b>（传了也会被忽略，且越权风险由它兜住）。</p>
 */
@RestController
@RequestMapping("/api")
@Slf4j
public class AuthController {

    private final UserService userService;
    private final TokenService tokenService;
    private final RoomService roomService;

    public AuthController(UserService userService, TokenService tokenService, RoomService roomService) {
        this.userService = userService;
        this.tokenService = tokenService;
        this.roomService = roomService;
    }

    @PostMapping("/register")
    public Result<?> register(@Valid @RequestBody RegisterDTO dto) {
        return Result.success(userService.register(dto));
    }

    /**
     * 登录：<b>只返回 token 对</b>（{@code accessToken} / {@code refreshToken} / {@code expiresIn}）。
     *
     * <p>前端拿到后存起来，之后每个请求带 {@code Authorization: Bearer <accessToken>}；
     * 想知道"我是谁"再调 {@link #me}。</p>
     *
     * <p><b>单点登录</b>：{@code issue} 会把该账号"当前有效的 accessToken"覆盖成这一把
     * （旧 token 立刻失效），紧接着把旧设备<b>已经连着的</b> WebSocket 踢掉并推一条
     * {@code kick}。旧设备因此会弹"该账号在别处登录"并退回登录页。</p>
     */
    @PostMapping("/login")
    public Result<?> login(@Valid @RequestBody LoginDTO dto) {
        UserVO user = userService.authenticate(dto);
        TokenPair pair = tokenService.issue(user.getId(), user.getUsername(), user.getRole());
        // 顺序很重要：先换会话（旧 token 作废），再踢旧连接。
        // 反过来的话，旧设备被踢掉后可能立刻用旧 token 重连成功。
        try {
            roomService.kickUser(user.getId(), "该账号在别处登录，请重新登录");
        } catch (Exception e) {
            // 踢连接失败不能影响登录本身（新设备已经拿到 token 了）
            log.warn("踢掉旧连接失败（不影响登录）：{}", e.getMessage());
        }
        return Result.success(pair);
    }

    /**
     * 当前登录用户的信息（id / username / nickname / role）。
     *
     * <p>前端登录后调它来渲染昵称、用户名；也用于验证 token 还有效。</p>
     */
    @GetMapping("/user/me")
    public Result<?> me(@RequestAttribute("userId") Long userId) {
        UserVO vo = userService.getUserInfo(userId);
        if (vo == null) {
            return Result.notFound("用户不存在");
        }
        return Result.success(vo);
    }

    /**
     * 修改昵称。
     *
     * <p>身份取自 token；{@code /user/nickname} 后面<b>不带 id</b> —— 只能改自己。</p>
     */
    @PostMapping("/user/nickname")
    public Result<?> updateNickname(@Valid @RequestBody UpdateNicknameDTO dto,
                                    @RequestAttribute("userId") Long userId) {
        userService.updateNickname(dto, userId, userId);
        return Result.success();
    }

    /**
     * 修改密码。
     *
     * <p>身份取自 token；{@code /password} 后面<b>不带 id</b>。</p>
     */
    @PutMapping("/password")
    public Result<?> updatePassword(@Valid @RequestBody UpdatePasswordDTO updatePasswordDTO,
                                    @RequestAttribute("userId") Long userId) {
        userService.updatePassword(updatePasswordDTO, userId, userId);
        return Result.success();
    }

    /**
     * 登出：把当前 accessToken 拉黑。
     *
     * <p>本接口在拦截器放行名单里（不需要"已登录"就能调），但它自己要读 {@code Authorization} 头
     * 才知道拉黑哪一个 token。</p>
     */
    @PostMapping("/logout")
    public Result<?> logout(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        tokenService.logout(authorization);
        return Result.success();
    }

    /**
     * 刷新：用 refreshToken 换一对新 token。
     *
     * <p>refreshToken 走<b>请求体</b>，不是 {@code Authorization} 头。</p>
     */
    @PostMapping("/refresh")
    public Result<?> refresh(@Valid @RequestBody RefreshDTO dto) {
        return Result.success(tokenService.refresh(dto.getRefreshToken()));
    }
}
