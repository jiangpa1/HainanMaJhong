package hainanMahjong.dto;

import lombok.Data;
import lombok.ToString;

import javax.validation.constraints.NotBlank;

@Data
public class RefreshDTO {
    @ToString.Exclude
    @NotBlank(message = "refreshToken 不能为空")
    private String refreshToken;
}

