package hainanMahjong.exception;

import hainanMahjong.common.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import javax.validation.ConstraintViolation;
import javax.validation.ConstraintViolationException;
import java.util.List;

/**
 * 全局异常处理：保证<b>任何</b>异常出口都返回统一的 {@link Result} 形状。
 *
 * <p><b>为什么必须有它</b>：本项目的响应口径是 {@code {code, message, data}}，
 * 但控制器里有 6 处 {@code @Valid}（{@code AuthController} 5 处 + {@code RecordsController} 1 处）。
 * 没有这个类时，参数校验失败会走 Spring 默认错误页，返回
 * {@code {timestamp, status, error, path}} —— 与项目口径不一致。
 * 更糟的是：Spring 会内部 forward 到 {@code /error}，而真实错误信息会被盖住。</p>
 *
 * <p><b>为什么不加 {@code @ResponseStatus}</b>：本项目的约定是 <b>HTTP 状态码恒为 200，
 * 业务码放在 {@code Result.code} 里</b> —— {@link hainanMahjong.interceptor.JwtInterceptor}
 * 的 401 就是这么写的（{@code response.setStatus(SC_OK)} + {@code Result.unauthorized(...)}）。
 * 这里保持一致，前端只需看 {@code code}。</p>
 *
 * <p><b>两类异常要分清</b>：</p>
 * <ul>
 *   <li>{@code @Valid @RequestBody} 失败 → {@link MethodArgumentNotValidException}</li>
 *   <li>{@code @Valid @ModelAttribute} 失败 → {@link BindException}
 *       （<b>不是同一个异常</b>，漏掉它 {@code RecordsController} 的分页校验就会漏网）</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务异常：用异常自带的 code（401/403/404/…）。 */
    @ExceptionHandler(BusinessException.class)
    public Result<?> handleBusiness(BusinessException e) {
        log.warn("业务异常 [{}] {}", e.getCode(), e.getMessage());
        return Result.build(e.getCode(), e.getMessage(), null);
    }

    /** {@code @Valid @RequestBody} 校验失败。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<?> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        String msg = firstMessage(e.getBindingResult().getAllErrors());
        log.warn("参数校验失败：{}", msg);
        return Result.paramError(msg);
    }

    /** {@code @Valid @ModelAttribute} / 表单绑定失败。 */
    @ExceptionHandler(BindException.class)
    public Result<?> handleBind(BindException e) {
        String msg = firstMessage(e.getBindingResult().getAllErrors());
        log.warn("参数绑定失败：{}", msg);
        return Result.paramError(msg);
    }

    /** {@code @Validated} 在方法参数上的校验失败（如 {@code @RequestParam} 上的约束）。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public Result<?> handleConstraintViolation(ConstraintViolationException e) {
        String msg = "参数不合法";
        for (ConstraintViolation<?> v : e.getConstraintViolations()) {
            msg = v.getMessage();
            break;
        }
        log.warn("约束校验失败：{}", msg);
        return Result.paramError(msg);
    }

    /** 请求体不是合法 JSON，或缺必填字段导致无法反序列化。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<?> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体无法解析：{}", e.getMessage());
        return Result.paramError("请求体格式错误");
    }

    /** 缺必填的 query 参数。 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<?> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("缺少请求参数：{}", e.getParameterName());
        return Result.paramError("缺少参数：" + e.getParameterName());
    }

    /** 路径变量/参数类型对不上（如 {@code /api/records/abc} 期望 long）。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<?> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("参数类型不匹配：{}", e.getName());
        return Result.paramError("参数类型错误：" + e.getName());
    }

    /** 兜底：未预期的异常。记完整堆栈，但只给前端一句通用提示（不泄露内部细节）。 */
    @ExceptionHandler(Exception.class)
    public Result<?> handleUnexpected(Exception e) {
        log.error("未预期异常", e);
        return Result.error("服务器内部错误，请稍后重试");
    }

    /** 取第一条校验错误信息（字段名 + 提示），没有则给个通用文案。 */
    private static String firstMessage(List<ObjectError> errors) {
        if (errors == null || errors.isEmpty()) {
            return "参数不合法";
        }
        ObjectError first = errors.get(0);
        String msg = first.getDefaultMessage();
        if (first instanceof FieldError fe && msg != null) {
            return fe.getField() + " " + msg;
        }
        return msg == null ? "参数不合法" : msg;
    }
}
