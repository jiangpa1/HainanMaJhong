package hainanMahjong.service;

import hainanMahjong.dto.LoginDTO;
import hainanMahjong.dto.RegisterDTO;
import hainanMahjong.dto.UpdateNicknameDTO;
import hainanMahjong.dto.UpdatePasswordDTO;
import hainanMahjong.vo.UserVO;


public interface UserService {
    UserVO register(RegisterDTO dto);

    UserVO authenticate(LoginDTO dto);

    /**
     * 按 userId 取用户信息（供 {@code GET /api/user/me} 用）。
     *
     * <p>存在的意义：登录只返回 {@link hainanMahjong.vo.TokenPair}（不含 userId/nickname），
     * 前端拿不到"我是谁"。与其把用户信息塞进 token 让前端自己解，不如提供一个"当前用户"接口 ——
     * 前端只存 token，展示用的昵称/用户名每次向这里要。</p>
     *
     * @return 用户信息；查不到返回 null
     */
    UserVO getUserInfo(Long userId);

    void updateNickname(UpdateNicknameDTO dto, Long id, Long userId);

    void updatePassword(UpdatePasswordDTO updatePasswordDTO, Long id, Long userId);
}
