package org.las.sso.api;

import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.las.sso.admin.AdminUserManagementException;
import org.las.sso.account.AccountDeletionConflictException;
import org.las.sso.account.IdempotencyConflictException;
import org.las.sso.account.PasswordPolicyException;
import org.las.sso.account.RecoveryFlowException;
import org.las.sso.account.UsernameUnavailableException;
import org.las.sso.security.CaptchaInvalidException;
import org.las.sso.security.RateLimitExceededException;
import org.las.sso.security.RequestOriginInvalidException;
import org.las.sso.security.RecentAuthenticationException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiProblemHandler {

    @ExceptionHandler(AdminUserManagementException.class)
    ResponseEntity<ProblemDetail> adminUserManagement(
            AdminUserManagementException exception, HttpServletRequest request) {
        return switch (exception.code()) {
            case "ADMIN_TARGET_NOT_FOUND" -> problem(
                    HttpStatus.NOT_FOUND, exception.code(),
                    "账号不存在", "目标账号不存在。", request, null);
            case "ADMIN_ACCESS_DENIED", "ADMIN_SELF_ACTION_FORBIDDEN" -> problem(
                    HttpStatus.FORBIDDEN, exception.code(),
                    "不允许执行此操作", "管理员不能对该账号执行此操作。", request, null);
            case "ADMIN_TARGET_IS_ADMIN" -> problem(
                    HttpStatus.CONFLICT, exception.code(),
                    "不能操作管理员账号", "管理员账号必须通过离线维护流程处理。", request, null);
            case "INVALID_HOLD_UNTIL" -> problem(
                    HttpStatus.BAD_REQUEST, exception.code(),
                    "暂缓期限无效", "暂缓截止时间必须在当前时间之后、90 天以内。", request, null);
            default -> problem(
                    HttpStatus.CONFLICT, "ACCOUNT_STATE_CONFLICT",
                    "账号状态已经变化", "请刷新用户详情后重试。", request, null);
        };
    }

    @ExceptionHandler(AccountDeletionConflictException.class)
    ResponseEntity<ProblemDetail> deletionConflict(
            AccountDeletionConflictException exception, HttpServletRequest request) {
        return switch (exception.code()) {
            case "ADMIN_DELETION_FORBIDDEN" -> problem(
                    HttpStatus.CONFLICT, exception.code(),
                    "管理员账号不能自助注销", "管理员账号必须通过运维维护流程处理。", request, null);
            default -> problem(
                    HttpStatus.CONFLICT, exception.code(),
                    "注销申请状态冲突", "当前账号的注销申请状态不允许此操作。", request, null);
        };
    }

    @ExceptionHandler(RequestOriginInvalidException.class)
    ResponseEntity<ProblemDetail> originInvalid(
            RequestOriginInvalidException exception, HttpServletRequest request) {
        return problem(HttpStatus.FORBIDDEN, "CSRF_INVALID",
                "请求来源无效", "请求必须来自身份中心同源页面。", request, null);
    }

    @ExceptionHandler(CaptchaInvalidException.class)
    ResponseEntity<ProblemDetail> captchaInvalid(
            CaptchaInvalidException exception, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "CAPTCHA_INVALID",
                "人机验证无效", "Challenge 无效、过期或已使用。", request, null);
    }

    @ExceptionHandler(UsernameUnavailableException.class)
    ResponseEntity<ProblemDetail> usernameUnavailable(
            UsernameUnavailableException exception, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "USERNAME_UNAVAILABLE",
                "用户名不可用", "请选择其他用户名。", request, null);
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<ProblemDetail> idempotencyConflict(
            IdempotencyConflictException exception, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                "幂等键已被使用", "请勿把同一个 Idempotency-Key 用于不同请求。", request, null);
    }

    @ExceptionHandler(RateLimitExceededException.class)
    ResponseEntity<ProblemDetail> rateLimited(
            RateLimitExceededException exception, HttpServletRequest request) {
        long retryAfter = Math.max(1, Duration.between(
                OffsetDateTime.now(ZoneOffset.UTC), exception.resetsAt()).toSeconds());
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter));
        return problem(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                "请求过于频繁", "请在 Retry-After 指示的时间后重试。", request, headers);
    }

    @ExceptionHandler(RecentAuthenticationException.class)
    ResponseEntity<ProblemDetail> recentAuthentication(
            RecentAuthenticationException exception, HttpServletRequest request) {
        return switch (exception.code()) {
            case "RECENT_AUTHENTICATION_REQUIRED" -> problem(
                    HttpStatus.FORBIDDEN, exception.code(),
                    "需要近期认证", "请重新输入当前密码，然后再次确认操作。", request, null);
            case "PASSWORD_CHANGE_REQUIRED" -> problem(
                    HttpStatus.FORBIDDEN, exception.code(),
                    "必须先修改密码", "临时密码会话只能完成首次改密。", request, null);
            default -> problem(
                    HttpStatus.UNAUTHORIZED, "REAUTHENTICATION_FAILED",
                    "认证失败", "当前密码不正确。", request, null);
        };
    }

    @ExceptionHandler(RecoveryFlowException.class)
    ResponseEntity<ProblemDetail> recoveryFlow(
            RecoveryFlowException exception, HttpServletRequest request) {
        return switch (exception.code()) {
            case "RECOVERY_CREDENTIAL_INVALID" -> problem(
                    HttpStatus.BAD_REQUEST, exception.code(),
                    "恢复凭据无效", "用户名或恢复凭据不正确、已使用或已失效。", request, null);
            case "CAPTCHA_REQUIRED" -> problem(
                    HttpStatus.FORBIDDEN, exception.code(),
                    "需要人机验证", "请完成人机验证后再次提交恢复凭据。", request, null);
            case "SESSION_EXPIRED" -> problem(
                    HttpStatus.UNAUTHORIZED, exception.code(),
                    "恢复会话已失效", "请重新验证恢复凭据。", request, null);
            case "NEW_PASSWORD_REUSED" -> problem(
                    HttpStatus.BAD_REQUEST, exception.code(),
                    "不能继续使用旧密码", "请设置一个与旧密码不同的新密码。", request, null);
            case "PASSWORD_TOO_SHORT", "PASSWORD_TOO_LONG", "PASSWORD_EQUALS_USERNAME",
                    "PASSWORD_COMPROMISED", "PASSWORD_REQUIRED" -> problem(
                    HttpStatus.BAD_REQUEST, exception.code(),
                    "新密码不符合要求", "请按照密码字段提示设置新的密码。", request, null);
            default -> problem(
                    HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "请求字段无效", "请检查请求字段格式。", request, null);
        };
    }

    @ExceptionHandler({
            IllegalArgumentException.class,
            PasswordPolicyException.class,
            MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class,
            MissingRequestHeaderException.class
    })
    ResponseEntity<ProblemDetail> validation(Exception exception, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                "请求字段无效", "请检查请求字段格式。", request, null);
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ProblemDetail> databaseUnavailable(
            DataAccessException exception, HttpServletRequest request) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "IDENTITY_SERVICE_UNAVAILABLE",
                "身份服务暂时不可用", "请稍后重试。", request, null);
    }

    private ResponseEntity<ProblemDetail> problem(
            HttpStatus status,
            String code,
            String title,
            String detail,
            HttpServletRequest request,
            HttpHeaders headers) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("https://auth.las.example/problems/" + code.toLowerCase().replace('_', '-')));
        problem.setTitle(title);
        problem.setProperty("code", code);
        Object requestId = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        problem.setProperty("requestId", requestId instanceof UUID ? requestId : null);
        HttpHeaders responseHeaders = headers == null ? new HttpHeaders() : headers;
        responseHeaders.setCacheControl("no-store");
        return new ResponseEntity<>(problem, responseHeaders, status);
    }
}
