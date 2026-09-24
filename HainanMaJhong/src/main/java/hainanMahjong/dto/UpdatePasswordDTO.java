package hainanMahjong.dto;

import lombok.Data;
import lombok.ToString;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@Data
public class UpdatePasswordDTO {
    @ToString.Exclude
    @NotBlank(message = "旧密码不能为空")
    @Size(min = 6, message = "密码长度至少6位数")
    private String oldPassword;
    @ToString.Exclude
    @NotBlank(message = "新密码不能为空")
    @Size(min = 6, message = "密码长度至少6位数")
    private String newPassword;
    @ToString.Exclude
    @NotBlank(message = "确认密码不能为空")
    @Size(min = 6, message = "密码长度至少6位数")
    private  String confirmNewPassword;
}
