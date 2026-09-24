package hainanMahjong.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import hainanMahjong.pojo.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper extends BaseMapper<User> {

    /**
     * 查询用户名；查不到返回 null。
     *
     * <p>只取一列，不走 {@code selectById}（那会把整行含 password 读出来）。</p>
     *
     * <p><b>注意</b>：机器人/游客的 userId 是 {@code <= 0}，主键查不到自然返回 null，
     * 但调用方仍应保留 {@code userId <= 0} 的短路，别依赖这个巧合。</p>
     */
    @Select("SELECT username FROM `user` WHERE id = #{userId}")
    String selectUsername(@Param("userId") Long userId);

    /** 查询昵称；未设置（或查不到）返回 null。 */
    @Select("SELECT nickname FROM `user` WHERE id = #{userId}")
    String selectNickname(@Param("userId") Long userId);
}
