package hainanMahjong.vo.recordvo;

import lombok.Data;
import java.util.List;

/** V2 摘要不携带手牌、番型 JSON、杠花明细。局号在 session 内唯一且结算后不可变。 */
public final class RecordsV2VO {
    private RecordsV2VO() {}

    @Data
    public static class Stats {
        private Long userId;
        private int seat;
        private long totalScore;
        private int winCount;
        private int highestFanValue;
        private String highestFan;
        private String nickname;
        private int rank;
    }

    @Data
    public static class Overview {
        private long sessionId;
        private String roomId;
        private int status;
        private int readThroughRoundNum;
        private int latestRoundNum;
        private String version;
        private List<Stats> players;
        private long totalScore;
        private int rank;
        private int winCount;
        private int highestFanValue;
        private String highestFan;
    }

    @Data
    public static class RoundKey {
        private long id;
        private int roundNum;
    }

    @Data
    public static class RoundSummary {
        private long roundId;
        private int roundNum;
        private boolean draw;
        private Integer winType;
        private Long winnerId;
        private Long loserId;
        private Integer totalFan;
        private String dealerSeat;
        private Integer windIdx;
        private List<Score> participants;
    }

    @Data
    public static class Score {
        private long roundId;
        private Long userId;
        private int seat;
        private int scoreChange;
        private boolean winner;
        private String nickname;
        private boolean me;
    }

    @Data
    public static class RoundPage {
        private long sessionId;
        private int readThroughRoundNum;
        private String version;
        private List<RoundSummary> rounds;
        private boolean hasMore;
        private int nextAfterRoundNum;
        private int pageSize;
    }
}
