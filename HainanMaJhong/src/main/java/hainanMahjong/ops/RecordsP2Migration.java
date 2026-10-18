package hainanMahjong.ops;

import hainanMahjong.pojo.GameSessions;
import hainanMahjong.service.impl.RecordProjection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/** Explicit standalone operator tool, never runs during application startup. */
public final class RecordsP2Migration {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final RecordProjection projection;
    public RecordsP2Migration(javax.sql.DataSource source) {
        jdbc=new JdbcTemplate(source);
        tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setTimeout(30);
        projection=new RecordProjection(jdbc);
    }
    public static void main(String[] args) {
        // Standalone launch does not apply Spring's application.yml logging settings.
        org.slf4j.Logger root=org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        if(root instanceof ch.qos.logback.classic.Logger logger) logger.setLevel(ch.qos.logback.classic.Level.WARN);
        if(args.length==0) throw new IllegalArgumentException("inspect | prepare | backfill [afterId] [limit<=500] | verify [afterId] [limit<=500] | promote | revoke");
        String url=Objects.requireNonNull(System.getenv("RECORDS_P2_JDBC_URL"),"RECORDS_P2_JDBC_URL required");
        DriverManagerDataSource source=new DriverManagerDataSource(url,
                Objects.requireNonNull(System.getenv("RECORDS_P2_DB_USER"),"RECORDS_P2_DB_USER required"),
                Objects.requireNonNull(System.getenv("RECORDS_P2_DB_PASSWORD"),"RECORDS_P2_DB_PASSWORD required"));
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        RecordsP2Migration tool=new RecordsP2Migration(source);
        switch(args[0]) {
            case "inspect" -> {
                tool.inspect();
                if(args.length>2) tool.explain(Long.parseLong(args[1]),Long.parseLong(args[2]));
            }
            case "prepare" -> { tool.jdbc.execute(RecordProjection.PLAYERS_DDL); tool.jdbc.execute(RecordProjection.VERSION_DDL); }
            case "backfill", "verify" -> {
                long after=args.length>1?Long.parseLong(args[1]):0;
                int limit=args.length>2?Integer.parseInt(args[2]):100;
                if(after<0 || limit<1 || limit>500) throw new IllegalArgumentException("Invalid batch bounds");
                System.out.println("lastSessionId="+tool.batch(after,limit,args[0].equals("backfill")));
            }
            case "promote" -> tool.promote();
            case "revoke" -> tool.jdbc.update("DELETE FROM game_record_versions WHERE session_id=0");
            default -> throw new IllegalArgumentException("Unknown operation");
        }
    }
    public void inspect() {
        System.out.println("mysql="+jdbc.queryForObject("SELECT VERSION()",String.class));
        for(String table:List.of("game_sessions","game_rounds","game_round_scores")) {
            System.out.println(table+" rows="+jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class));
            System.out.println(jdbc.queryForList("SHOW INDEX FROM "+table));
        }
        System.out.println("duplicateRounds="+jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT session_id,round_num FROM game_rounds GROUP BY session_id,round_num HAVING COUNT(*)>1) x",Long.class));
        System.out.println("duplicateScores="+jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT round_id,user_id FROM game_round_scores GROUP BY round_id,user_id HAVING COUNT(*)>1) x",Long.class));
        System.out.println("incompleteRounds="+jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT r.id FROM game_rounds r LEFT JOIN game_round_scores s ON s.round_id=r.id GROUP BY r.id HAVING COUNT(s.id)<>4 OR COUNT(DISTINCT s.user_id)<>4 OR COUNT(DISTINCT s.seat)<>4 OR MIN(s.seat)<>0 OR MAX(s.seat)<>3) x",Long.class));
        System.out.println("orphanScores="+jdbc.queryForObject("SELECT COUNT(*) FROM game_round_scores s LEFT JOIN game_rounds r ON r.id=s.round_id WHERE r.id IS NULL",Long.class));
    }
    public void explain(long user,long session) {
        for(String sql:List.of(
                "SELECT id FROM game_sessions WHERE JSON_CONTAINS(player_ids,CAST(? AS JSON)) ORDER BY id DESC LIMIT 10",
                "SELECT session_id FROM game_session_players WHERE user_id=? ORDER BY session_id DESC LIMIT 10"))
            System.out.println(jdbc.queryForObject("EXPLAIN FORMAT=JSON "+sql,String.class,user));
        System.out.println(jdbc.queryForObject("EXPLAIN FORMAT=JSON SELECT id FROM game_rounds WHERE session_id=? AND round_num>0 ORDER BY round_num LIMIT 11",String.class,session));
        System.out.println(jdbc.queryForObject("EXPLAIN FORMAT=JSON SELECT * FROM game_session_players WHERE session_id=?",String.class,session));
    }
    public long batch(long after,int limit,boolean rebuild) {
        List<Long> ids=jdbc.queryForList("SELECT id FROM game_sessions WHERE id>? ORDER BY id LIMIT ?",Long.class,after,limit);
        for(long id:ids) {
            tx.executeWithoutResult(status -> {
                GameSessions session=jdbc.queryForObject("SELECT id,player_ids FROM game_sessions WHERE id=? FOR UPDATE",(rs,n)-> {
                    GameSessions s=new GameSessions(); s.setId(rs.getLong(1)); s.setPlayerIds(rs.getString(2)); return s;
                },id);
                if(rebuild) projection.rebuild(session); else projection.verify(session);
            });
        }
        System.out.println("checked="+ids.size());
        return ids.isEmpty()?after:ids.get(ids.size()-1);
    }
    public void promote() {
        // Revoke before a new audit so a failed comparison cannot leave an old gate open.
        jdbc.update("DELETE FROM game_record_versions WHERE session_id=0");
        long after=0;
        while(true) {
            long next=batch(after,100,false);
            if(next==after) break;
            after=next;
        }
        long missing=jdbc.queryForObject("SELECT COUNT(*) FROM game_sessions g LEFT JOIN game_record_versions v ON v.session_id=g.id WHERE v.session_id IS NULL",Long.class);
        if(missing!=0) throw new IllegalStateException("Unmigrated sessions remain: "+missing);
        jdbc.update("INSERT INTO game_record_versions(session_id,through_round,revision) VALUES(0,0,1)");
        System.out.println("All session projections verified; read gate opened.");
    }
}
