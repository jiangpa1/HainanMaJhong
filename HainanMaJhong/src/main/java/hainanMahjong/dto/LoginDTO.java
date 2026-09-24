package hainanMahjong.dto;

import lombok.Data;
import lombok.ToString;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@Data
public class LoginDTO {
    @NotBlank(message = "用户名不能为空")
    @Size(min = 4, max = 20, message = "用户名长度为4-20")
    private String username;
    @ToString.Exclude
    @NotBlank(message = "密码不能为空")
    @Size(min = 6, message = "密码长度至少6位数")
    private String password;
}
