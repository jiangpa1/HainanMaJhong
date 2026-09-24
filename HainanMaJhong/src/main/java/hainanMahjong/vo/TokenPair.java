package hainanMahjong.vo;

import lombok.Data;
import lombok.ToString;

@Data
public class TokenPair {
    @ToString.Exclude
    private String accessToken;
    @ToString.Exclude
    private String refreshToken;
    private Long expiresIn;
}

