package hainanjong.game;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对局中某一把的完整快照，用于 Redis 断线恢复。
 * 包含：牌墙、出牌堆、四家手牌/副露/花牌、当前出牌者及其阶段。
 */
public class GameSnapshot {

    public List<Integer> deck = new ArrayList<Integer>();        // 剩余牌墙（保持顺序）
    public List<Integer> discardPile = new ArrayList<Integer>(); // 出牌堆（保持顺序）
    public Map<String, PlayerState> players = new LinkedHashMap<String, PlayerState>(); // 座位名 -> 玩家
    public Map<String, List<Integer>> discards = new LinkedHashMap<String, List<Integer>>(); // 座位名 -> 各家弃牌（保持顺序）
    public String currentSeat;   // 当前出牌者座位名
    public boolean skipDraw;     // 是否跳过抓牌（碰/吃后）
    public boolean allowWin;     // 是否允许自摸/杠
    public String phase;         // "TURN"=回合起点 / "RESPOND"=弃牌后等待响应

    public static class PlayerState {
        public List<Integer> hand = new ArrayList<Integer>();
        public List<MeldState> melds = new ArrayList<MeldState>();
        public List<Integer> flowers = new ArrayList<Integer>();
        public int lastDrawn = -1;
        public int reportMode; // 报听：0 无 / 1 天听 / 2 地听
    }

    public static class MeldState {
        public String type;               // Meld.Type 名称（CHI/PENG/GANG/AN_GANG/BU_GANG）
        public List<Integer> tiles = new ArrayList<Integer>();
        public String from;               // 来源座位名，暗杠/补杠为 null
    }
}
