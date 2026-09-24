package hainanMahjong.vo;

import lombok.Data;

@Data
public class UserVO {
    private Long id;
    private Integer role;
    private String username;
    private String nickname;
}