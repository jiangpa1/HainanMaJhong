package hainanMahjong.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import hainanMahjong.engine.model.GameSnapshot;
import hainanMahjong.engine.model.Seat;
import hainanMahjong.repository.RoomSnapshotRepository;
import hainanMahjong.room.Member;
import hainanMahjong.room.MemberInfo;
import hainanMahjong.room.RedisRoom;
import hainanMahjong.room.Room;
import hainanMahjong.rules.DealerFlow;
import hainanMahjong.service.RoomSnapshotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 房间快照的 Redis 落盘。
 *
 * <p>纯"读 {@link Room} → 组装 {@link RedisRoom} → 写 Redis"，无锁、不改房间状态、
 * 不接触房间注册表。</p>
 */
@Service
public class RoomSnapshotServiceImpl implements RoomSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(RoomSnapshotServiceImpl.class);

    private final RoomSnapshotRepository redis;
    private final ObjectMapper mapper = new ObjectMapper();

    public RoomSnapshotServiceImpl(RoomSnapshotRepository redis) {
        this.redis = redis;
    }

    @Override
    public void save(Room room, GameSnapshot snap) {
        try {
            RedisRoom rs = new RedisRoom();
            rs.hostUserId = room.hostUserId;
            rs.state = room.state.name();
            rs.blockNo = room.blockNo;
            rs.sessionId = room.sessionId;
            rs.currentHand = room.currentHand;
            rs.dealer = room.currentDealer == null ? null : room.currentDealer.name();
            rs.cfg = room.cfg;
            DealerFlow f = room.flow;
            if (f != null) {
                rs.firstDealer = f.firstDealer().name();
                rs.bottom = f.bottom;
                rs.windIdx = f.windIdx();
                rs.flowHandNo = f.handNo;
                rs.firstDealerBegins = f.begins();
                rs.handsPlayed = f.handsPlayed();
            }
            for (Seat s : Seat.values()) {
                rs.coins.put(s.name(), room.coins.get(s) == null ? 0 : room.coins.get(s));
            }
            for (Member m : room.members.values()) {
                MemberInfo mi = new MemberInfo();
                mi.seat = m.seat.name();
                mi.userId = m.userId;
                mi.nickname = m.nickname;
                rs.members.add(mi);
            }
            rs.snapshot = snap;
            redis.saveRoomState(room.code, mapper.writeValueAsString(rs));
        } catch (Exception e) {
            log.warn("保存 Redis 多人房间失败({}): {}", room.code, e.getMessage());
        }
    }
}
