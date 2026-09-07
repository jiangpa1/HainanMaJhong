package hainanjong.game;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 简单贪心机器人：能吃就吃、能碰就碰、能杠就杠、能胡就胡，出牌优先打孤张。
 * 用于演示整局流程。
 */
public class BotController implements PlayerController {

    private final Random random;

    public BotController() {
        this.random = new Random();
    }

    public BotController(long seed) {
        this.random = new Random(seed);
    }

    @Override
    public void onDiscardTurn(Seat seat, List<Integer> hand, int drawnTile, Responder r) {
        r.discard(pickDiscard(hand));
    }

    @Override
    public void onActionChance(Seat seat, int tile, List<Action> options, Responder r) {
        for (Action a : options) {
            if (a.type == Action.Type.HU) {
                r.act(a);
                return;
            }
        }
        for (Action a : options) {
            if (a.type == Action.Type.GANG) {
                r.act(a);
                return;
            }
        }
        for (Action a : options) {
            if (a.type == Action.Type.PENG) {
                r.act(a);
                return;
            }
        }
        for (Action a : options) {
            if (a.type == Action.Type.CHI) {
                r.act(a);
                return;
            }
        }
        r.pass();
    }

    @Override
    public void onDrawChance(Seat seat, int drawnTile, List<Action> options, Responder r) {
        for (Action a : options) {
            if (a.type == Action.Type.HU) {
                r.act(a);
                return;
            }
        }
        for (Action a : options) {
            if (a.type == Action.Type.AN_GANG || a.type == Action.Type.BU_GANG) {
                r.act(a);
                return;
            }
        }
        r.pass();
    }

    private int pickDiscard(List<Integer> hand) {
        Map<Integer, Integer> cnt = new HashMap<Integer, Integer>();
        for (int t : hand) {
            Integer c = cnt.get(t);
            cnt.put(t, c == null ? 1 : c + 1);
        }

        List<Integer> honorSingle = new ArrayList<Integer>();
        List<Integer> suitIsolated = new ArrayList<Integer>();
        List<Integer> anySingle = new ArrayList<Integer>();

        for (int t : hand) {
            if (cnt.get(t) != 1) continue;
            if (t >= 27) {
                honorSingle.add(t);
            } else {
                boolean iso = (t % 9 == 0 || !hand.contains(t - 1)) && (t % 9 == 8 || !hand.contains(t + 1));
                if (iso) suitIsolated.add(t);
                anySingle.add(t);
            }
        }

        if (!honorSingle.isEmpty()) return honorSingle.get(random.nextInt(honorSingle.size()));
        if (!suitIsolated.isEmpty()) return suitIsolated.get(random.nextInt(suitIsolated.size()));
        if (!anySingle.isEmpty()) return anySingle.get(random.nextInt(anySingle.size()));
        return hand.get(random.nextInt(hand.size()));
    }
}
