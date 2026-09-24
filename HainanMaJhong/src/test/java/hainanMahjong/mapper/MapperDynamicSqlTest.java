package hainanMahjong.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MyBatis <b>注解式动态 SQL</b> 的写法约束（纯反射，不依赖 Spring / 数据库）。
 *
 * <p><b>为什么需要这个测试</b>：MyBatis 的 {@code XMLLanguageDriver} 只在注解内容
 * <b>以 {@code <script>} 开头</b>时才把它当动态 SQL 解析：</p>
 *
 * <pre>
 * public SqlSource createSqlSource(Configuration cfg, String script, Class&lt;?&gt; type) {
 *     if (script.startsWith("&lt;script&gt;")) { ...按 XML 解析... }
 *     else { ...整串当普通 SQL，不做任何标签处理... }
 * }
 * </pre>
 *
 * <p>所以 {@code @Select("SELECT ... &lt;foreach ...&gt;...&lt;/foreach&gt;")} 会把
 * {@code <foreach>} 原样拼进 SQL 发给数据库 —— <b>语法错误、接口 500</b>。
 * 这个坑真实发生过：战绩列表在"还没有战绩"时一切正常（空列表提前返回，
 * 根本不执行那条 SQL），一旦打过牌就变成「服务器内部错误」，非常难往这个方向想。</p>
 *
 * <p>本测试扫描所有 Mapper 接口的注解 SQL，只要出现动态标签就要求整串以
 * {@code <script>} 开头。它不依赖数据库，因此能在 CI 里稳定拦住这类回归。</p>
 */
class MapperDynamicSqlTest {

    /** 动态 SQL 标签（出现这些就必须有 &lt;script&gt; 包裹） */
    private static final String[] DYNAMIC_TAGS = {
            "<foreach", "<if", "<choose", "<when", "<otherwise", "<where", "<set", "<trim", "<bind"
    };

    /** 项目里所有用了注解 SQL 的 Mapper */
    private static final Class<?>[] MAPPERS = {
            GameSessionsMapper.class,
            GameRoundsMapper.class,
            UserMapper.class,
    };

    @Test
    @DisplayName("注解 SQL 里用了动态标签时，必须以 <script> 开头")
    void annotationSqlWithDynamicTagsMustStartWithScript() {
        List<String> bad = new ArrayList<>();

        for (Class<?> mapper : MAPPERS) {
            for (Method m : mapper.getDeclaredMethods()) {
                for (Annotation a : m.getAnnotations()) {
                    String sql = sqlOf(a);
                    if (sql == null) {
                        continue;
                    }
                    if (!hasDynamicTag(sql)) {
                        continue;
                    }
                    if (!sql.trim().startsWith("<script>")) {
                        bad.add(mapper.getSimpleName() + "." + m.getName());
                    }
                }
            }
        }

        assertTrue(bad.isEmpty(),
                "这些注解 SQL 用了 <foreach>/<if> 等动态标签但没以 <script> 开头，"
                        + "运行时会被当成普通 SQL 直接语法错误（战绩接口 500 就是这个原因）：" + bad);
    }

    @Test
    @DisplayName("用 <script> 包裹的注解 SQL 里，标签是配平的")
    void scriptWrappedSqlIsBalanced() {
        for (Class<?> mapper : MAPPERS) {
            for (Method m : mapper.getDeclaredMethods()) {
                for (Annotation a : m.getAnnotations()) {
                    String sql = sqlOf(a);
                    if (sql == null || !sql.trim().startsWith("<script>")) {
                        continue;
                    }
                    String name = mapper.getSimpleName() + "." + m.getName();
                    assertTrue(sql.trim().endsWith("</script>"), name + " 的 <script> 没有闭合");
                    assertTrue(count(sql, "<foreach") == count(sql, "</foreach>"),
                            name + " 的 <foreach> 开闭数量不一致");
                    assertFalse(sql.contains("<<"), name + " 里出现了疑似拼串错误（<<）");
                }
            }
        }
    }

    /**
     * 真正让 MyBatis 把注解 SQL 解析成 SqlSource，并展开 {@code <foreach>}。
     *
     * <p>上面那条只检查"写法"，这条检查"结果"：展开后 SQL 里必须是普通的
     * {@code IN ( ? , ? , ? )}，不能残留 {@code foreach} 字样。
     * 不需要数据库 —— 只建 Configuration 和 SqlSource，不执行。</p>
     */
    @Test
    @DisplayName("selectTotals 的 <foreach> 能被真正展开成 IN (?, ?, ?)")
    void foreachIsExpandedIntoPlaceholders() throws Exception {
        Method m = GameSessionsMapper.class.getDeclaredMethod("selectTotals", java.util.Collection.class);
        Select select = m.getAnnotation(Select.class);
        assertNotNull(select, "selectTotals 上应当有 @Select");

        String script = String.join(" ", select.value());
        Configuration cfg = new Configuration();
        SqlSource src = new XMLLanguageDriver().createSqlSource(cfg, script, java.util.Map.class);

        java.util.Map<String, Object> params = new java.util.HashMap<>();
        params.put("sessionIds", java.util.Arrays.asList(11L, 22L, 33L));
        BoundSql bound = src.getBoundSql(params);
        String sql = bound.getSql();

        assertFalse(sql.toLowerCase().contains("foreach"),
                "展开后的 SQL 里不该残留 foreach —— 说明动态标签没被解析：" + sql);
        assertTrue(sql.contains("IN"),
                "展开后应当还有 IN 子句：" + sql);
        // 三个 id → 三个占位符
        assertTrue(bound.getParameterMappings().size() >= 3,
                "三个 sessionId 应当产生至少 3 个参数占位：" + bound.getParameterMappings());
    }

    /** 取注解上的 SQL 字符串；不是 SQL 注解返回 null。 */
    private static String sqlOf(Annotation a) {
        if (a instanceof Select) {
            return String.join(" ", ((Select) a).value());
        }
        if (a instanceof Update) {
            return String.join(" ", ((Update) a).value());
        }
        if (a instanceof Insert) {
            return String.join(" ", ((Insert) a).value());
        }
        if (a instanceof Delete) {
            return String.join(" ", ((Delete) a).value());
        }
        return null;
    }

    private static boolean hasDynamicTag(String sql) {
        String lower = sql.toLowerCase();
        for (String tag : DYNAMIC_TAGS) {
            if (lower.contains(tag)) {
                return true;
            }
        }
        return false;
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        int i = haystack.indexOf(needle);
        while (i >= 0) {
            n++;
            i = haystack.indexOf(needle, i + needle.length());
        }
        return n;
    }
}
