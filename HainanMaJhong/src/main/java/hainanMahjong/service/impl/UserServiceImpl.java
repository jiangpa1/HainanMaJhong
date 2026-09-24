package hainanMahjong.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import hainanMahjong.common.CacheKeys;
import hainanMahjong.dto.UpdateNicknameDTO;
import hainanMahjong.dto.UpdatePasswordDTO;
import hainanMahjong.pojo.User;
import hainanMahjong.dto.LoginDTO;
import hainanMahjong.dto.RegisterDTO;
import hainanMahjong.exception.BusinessException;
import hainanMahjong.mapper.UserMapper;
import hainanMahjong.service.UserService;
import hainanMahjong.utils.PasswordUtil;

import hainanMahjong.vo.UserVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@Slf4j
public class UserServiceImpl implements UserService{

    private final UserMapper userMapper;
    private final PasswordUtil passwordUtil;
    private final StringRedisTemplate stringRedisTemplate;

    public UserServiceImpl(UserMapper userMapper, PasswordUtil passwordUtil, StringRedisTemplate stringRedisTemplate) {
        this.userMapper = userMapper;
        this.passwordUtil = passwordUtil;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public UserVO register(RegisterDTO dto) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, dto.getUsername());
        User exists = userMapper.selectOne(wrapper);

        if (exists != null) {
            throw new BusinessException(400, "用户名已存在！");
        }

        if(dto.getNickname() ==  null || dto.getNickname().trim().isEmpty()) {
            dto.setNickname(dto.getUsername());
        }

        String encodedPassword = passwordUtil.encode(dto.getPassword());

        User user = new User();

        user.setUsername(dto.getUsername());
        user.setPassword(encodedPassword);
        user.setNickname(dto.getNickname());
        user.setCreateTime(LocalDateTime.now());

        userMapper.insert(user);

        return toUserVO(user);
    }

    @Override
    public UserVO authenticate(LoginDTO dto) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, dto.getUsername()));
        if (user == null) {
            throw new BusinessException(400, "用户名或密码错误");
        }

        if (!passwordUtil.matches(dto.getPassword(), user.getPassword())) {
            throw new BusinessException(400, "用户名或密码错误");
        }

        // 老用户：上面这次校验走的是旧的无盐 SHA-256，顺手把密文升级成 BCrypt。
        // 不做这一步的话，旧密文会永远留在库里，"兼容旧密码"就永远摘不掉。
        // upgrade 失败不影响本次登录（用户已经验过密码了），所以只记日志。
        if (passwordUtil.isLegacy(user.getPassword())) {
            try {
                user.setPassword(passwordUtil.encode(dto.getPassword()));
                userMapper.updateById(user);
            } catch (Exception e) {
                log.warn("升级密码密文为 BCrypt 失败，不影响本次登录", e);
            }
        }

        return toUserVO(user);
    }

    @Override
    public void updateNickname(UpdateNicknameDTO dto, Long id, Long userId) {
        String nickname = dto.getNickname();

        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(404, "用户不存在！");
        }

        if (!user.getId().equals(userId)) {
            throw new BusinessException(403, "无权操作他人账号");
        }
        user.setNickname(nickname);
        userMapper.updateById(user);
    }

    @Override
    public void updatePassword(UpdatePasswordDTO dto, Long id, Long userId) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(404, "用户不存在！");
        }

        if (!user.getId().equals(userId)) {
            throw new BusinessException(403, "无权操作他人账号");
        }

        String oldPassword = dto.getOldPassword();
        if (!passwordUtil.matches(dto.getOldPassword(), user.getPassword())) {
            throw new BusinessException(400, "原密码错误");
        }

        String newPassword = dto.getNewPassword();
        if (newPassword.equals(oldPassword)) {
            throw new BusinessException(400, "新密码不能与旧密码相同");
        }

        String confirmNewPassword = dto.getConfirmNewPassword();
        if (!confirmNewPassword.equals(newPassword)) {
            throw new BusinessException(400, "两次输入的密码不一致");
        }

        // 改密天然就是"写入新密文"，所以这一步顺带完成 BCrypt 升级，不需要额外判断旧格式
        user.setPassword(passwordUtil.encode(dto.getNewPassword()));
        userMapper.updateById(user);

        try {
            stringRedisTemplate.delete(CacheKeys.tokenRefresh(user.getId()));
        } catch (Exception e) {
            log.warn("删除 refresh key 失败，不影响此次返回", e);
        }
    }

    /**
     * 按 userId 取用户信息。
     *
     * <p>{@code GET /api/user/me} 用：登录只发 token，前端靠这个接口知道"我是谁"。</p>
     */
    @Override
    public UserVO getUserInfo(Long userId) {
        if (userId == null || userId <= 0) {
            return null;
        }
        User user = userMapper.selectById(userId);
        return user == null ? null : toUserVO(user);
    }
    private UserVO toUserVO(User user) {
        UserVO vo = new UserVO();
        BeanUtils.copyProperties(user, vo);
        return vo;
    }
}
