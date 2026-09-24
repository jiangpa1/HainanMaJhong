package hainanMahjong.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/**
 * 修改昵称的入参。
 *
 * <p><b>没有 id 字段</b>：要改谁由 token 决定（{@code @RequestAttribute("userId")}），
 * 调用方传不了、也就不可能改别人。</p>
 */
@Data
public class UpdateNicknameDTO {

    @NotBlank(message = "昵称不能为空")
    @Size(max = 32, message = "昵称最长 32 个字符")
    private String nickname;
}
