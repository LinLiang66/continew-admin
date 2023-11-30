/*
 * Copyright (c) 2022-present Charles7c Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package top.charles7c.continew.admin.monitor.interceptor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;
import org.springframework.web.util.WebUtils;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.LocalDateTimeUtil;
import cn.hutool.core.exceptions.ExceptionUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.servlet.JakartaServletUtil;
import cn.hutool.extra.spring.SpringUtil;
import cn.hutool.http.HttpStatus;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import top.charles7c.continew.admin.auth.model.req.AccountLoginReq;
import top.charles7c.continew.admin.common.constant.SysConstants;
import top.charles7c.continew.admin.common.model.dto.LogContext;
import top.charles7c.continew.admin.common.util.ServletUtils;
import top.charles7c.continew.admin.common.util.helper.LoginHelper;
import top.charles7c.continew.admin.common.util.holder.LogContextHolder;
import top.charles7c.continew.admin.monitor.annotation.Log;
import top.charles7c.continew.admin.monitor.config.properties.LogProperties;
import top.charles7c.continew.admin.monitor.enums.LogStatusEnum;
import top.charles7c.continew.admin.monitor.model.entity.LogDO;
import top.charles7c.continew.admin.system.service.UserService;
import top.charles7c.continew.starter.core.constant.StringConstants;
import top.charles7c.continew.starter.core.util.ExceptionUtils;
import top.charles7c.continew.starter.core.util.IpUtils;
import top.charles7c.continew.starter.extension.crud.model.resp.R;

/**
 * 系统日志拦截器
 *
 * @author Charles7c
 * @since 2022/12/24 21:14
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LogInterceptor implements HandlerInterceptor {

    private final UserService userService;
    private final LogProperties operationLogProperties;
    private static final String ENCRYPT_SYMBOL = "****************";

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
        @NonNull Object handler) {
        if (this.isNeedRecord(handler, request)) {
            // 记录时间
            this.logCreateTime();
        }
        return true;
    }

    @Override
    public void afterCompletion(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
        @NonNull Object handler, Exception e) {
        // 记录请求耗时及异常信息
        LogDO logDO = this.logElapsedTimeAndException();
        if (null == logDO) {
            return;
        }
        HandlerMethod handlerMethod = (HandlerMethod)handler;
        // 记录所属模块
        this.logModule(logDO, handlerMethod);
        // 记录日志描述
        this.logDescription(logDO, handlerMethod);
        // 记录请求信息
        this.logRequest(logDO, request);
        // 记录响应信息
        this.logResponse(logDO, response);
        // 保存系统日志
        SpringUtil.getApplicationContext().publishEvent(logDO);
    }

    /**
     * 记录时间
     */
    private void logCreateTime() {
        LogContext logContext = new LogContext();
        logContext.setCreateUser(LoginHelper.getUserId());
        logContext.setCreateTime(LocalDateTime.now());
        LogContextHolder.set(logContext);
    }

    /**
     * 记录请求耗时及异常详情
     *
     * @return 系统日志信息
     */
    private LogDO logElapsedTimeAndException() {
        LogContext logContext = LogContextHolder.get();
        if (null == logContext) {
            return null;
        }
        try {
            LogDO logDO = new LogDO();
            logDO.setCreateTime(logContext.getCreateTime());
            logDO.setElapsedTime(System.currentTimeMillis() - LocalDateTimeUtil.toEpochMilli(logDO.getCreateTime()));
            logDO.setStatus(LogStatusEnum.SUCCESS);
            // 记录错误信息（非未知异常不记录异常详情，只记录错误信息）
            String errorMsg = logContext.getErrorMsg();
            if (StrUtil.isNotBlank(errorMsg)) {
                logDO.setStatus(LogStatusEnum.FAILURE);
                logDO.setErrorMsg(errorMsg);
            }
            // 记录异常详情
            Throwable exception = logContext.getException();
            if (null != exception) {
                logDO.setStatus(LogStatusEnum.FAILURE);
                logDO.setExceptionDetail(ExceptionUtil.stacktraceToString(exception, -1));
            }
            return logDO;
        } finally {
            LogContextHolder.remove();
        }
    }

    /**
     * 记录所属模块
     *
     * @param logDO
     *            系统日志信息
     * @param handlerMethod
     *            处理器方法
     */
    private void logModule(LogDO logDO, HandlerMethod handlerMethod) {
        Tag classTag = handlerMethod.getBeanType().getDeclaredAnnotation(Tag.class);
        Log classLog = handlerMethod.getBeanType().getDeclaredAnnotation(Log.class);
        Log methodLog = handlerMethod.getMethodAnnotation(Log.class);
        // 例如：@Tag(name = "部门管理") -> 部门管理
        // （本框架代码规范）例如：@Tag(name = "部门管理 API") -> 部门管理
        if (null != classTag) {
            String name = classTag.name();
            logDO.setModule(
                StrUtil.isNotBlank(name) ? name.replace("API", StringConstants.EMPTY).trim() : "请在该接口类上指定所属模块");
        }
        // 例如：@Log(module = "部门管理") -> 部门管理
        if (null != classLog && StrUtil.isNotBlank(classLog.module())) {
            logDO.setModule(classLog.module());
        }
        if (null != methodLog && StrUtil.isNotBlank(methodLog.module())) {
            logDO.setModule(methodLog.module());
        }
    }

    /**
     * 记录日志描述
     *
     * @param logDO
     *            系统日志信息
     * @param handlerMethod
     *            处理器方法
     */
    private void logDescription(LogDO logDO, HandlerMethod handlerMethod) {
        Operation methodOperation = handlerMethod.getMethodAnnotation(Operation.class);
        Log methodLog = handlerMethod.getMethodAnnotation(Log.class);
        // 例如：@Operation(summary="新增部门") -> 新增部门
        if (null != methodOperation) {
            logDO.setDescription(StrUtil.blankToDefault(methodOperation.summary(), "请在该接口方法上指定日志描述"));
        }
        // 例如：@Log("新增部门") -> 新增部门
        if (null != methodLog && StrUtil.isNotBlank(methodLog.value())) {
            logDO.setDescription(methodLog.value());
        }
    }

    /**
     * 记录请求信息
     *
     * @param logDO
     *            系统日志信息
     * @param request
     *            请求对象
     */
    private void logRequest(LogDO logDO, HttpServletRequest request) {
        logDO.setRequestUrl(StrUtil.isBlank(request.getQueryString()) ? request.getRequestURL().toString() : request
            .getRequestURL().append(StringConstants.QUESTION_MARK).append(request.getQueryString()).toString());
        String method = request.getMethod();
        logDO.setRequestMethod(method);
        logDO.setRequestHeaders(this.desensitize(JakartaServletUtil.getHeaderMap(request)));
        String requestBody = this.getRequestBody(request);
        logDO.setCreateUser(ObjectUtil.defaultIfNull(logDO.getCreateUser(), LoginHelper.getUserId()));
        String requestURI = request.getRequestURI();
        if (requestURI.startsWith("/oauth")) {
            logDO.setCreateUser(null);
        }
        if (null == logDO.getCreateUser() && SysConstants.LOGIN_URI.equals(requestURI)) {
            AccountLoginReq loginReq = JSONUtil.toBean(requestBody, AccountLoginReq.class);
            logDO.setCreateUser(
                ExceptionUtils.exToNull(() -> userService.getByUsername(loginReq.getUsername()).getId()));
        }
        if (StrUtil.isNotBlank(requestBody)) {
            if (JSONUtil.isTypeJSONObject(requestBody)) {
                requestBody = this.desensitize(JSONUtil.parseObj(requestBody));
            } else if (JSONUtil.isTypeJSONArray(requestBody)) {
                JSONArray requestBodyJsonArr = JSONUtil.parseArray(requestBody);
                List<JSONObject> requestBodyJsonObjList = new ArrayList<>(requestBodyJsonArr.size());
                for (Object requestBodyJsonObj : requestBodyJsonArr) {
                    requestBodyJsonObjList
                        .add(JSONUtil.parseObj(this.desensitize(JSONUtil.parseObj(requestBodyJsonObj))));
                }
                requestBody = JSONUtil.toJsonStr(requestBodyJsonObjList);
            } else {
                requestBody = this.desensitize(JakartaServletUtil.getParamMap(request));
            }
            logDO.setRequestBody(requestBody);
        }
        logDO.setClientIp(JakartaServletUtil.getClientIP(request));
        logDO.setLocation(IpUtils.getCityInfo(logDO.getClientIp()));
        logDO.setBrowser(ServletUtils.getBrowser(request));
    }

    /**
     * 记录响应信息
     *
     * @param logDO
     *            系统日志信息
     * @param response
     *            响应对象
     */
    private void logResponse(LogDO logDO, HttpServletResponse response) {
        int status = response.getStatus();
        logDO.setStatusCode(status);
        logDO.setStatus(status >= HttpStatus.HTTP_BAD_REQUEST ? LogStatusEnum.FAILURE : logDO.getStatus());
        logDO.setResponseHeaders(this.desensitize(JakartaServletUtil.getHeadersMap(response)));
        // 响应体（不记录非 JSON 响应数据）
        String responseBody = this.getResponseBody(response);
        if (StrUtil.isNotBlank(responseBody) && JSONUtil.isTypeJSON(responseBody)) {
            logDO.setResponseBody(responseBody);
            // 业务状态码优先级高
            try {
                R result = JSONUtil.toBean(responseBody, R.class);
                logDO.setStatusCode(result.getCode());
                logDO.setStatus(result.isSuccess() ? LogStatusEnum.SUCCESS : LogStatusEnum.FAILURE);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 数据脱敏
     *
     * @param waitDesensitizeData
     *            待脱敏数据
     * @return 脱敏后的 JSON 字符串数据
     */
    @SuppressWarnings("unchecked")
    private String desensitize(Map waitDesensitizeData) {
        String desensitizeDataStr = JSONUtil.toJsonStr(waitDesensitizeData);
        try {
            if (CollUtil.isEmpty(waitDesensitizeData)) {
                return desensitizeDataStr;
            }
            for (String desensitizeProperty : operationLogProperties.getDesensitizeFields()) {
                waitDesensitizeData.computeIfPresent(desensitizeProperty, (k, v) -> ENCRYPT_SYMBOL);
                waitDesensitizeData.computeIfPresent(desensitizeProperty.toLowerCase(), (k, v) -> ENCRYPT_SYMBOL);
                waitDesensitizeData.computeIfPresent(desensitizeProperty.toUpperCase(), (k, v) -> ENCRYPT_SYMBOL);
            }
            return JSONUtil.toJsonStr(waitDesensitizeData);
        } catch (Exception ignored) {
        }
        return desensitizeDataStr;
    }

    /**
     * 获取请求体
     *
     * @param request
     *            请求对象
     * @return 请求体
     */
    private String getRequestBody(HttpServletRequest request) {
        String requestBody = "";
        ContentCachingRequestWrapper wrapper = WebUtils.getNativeRequest(request, ContentCachingRequestWrapper.class);
        if (null != wrapper) {
            requestBody = StrUtil.utf8Str(wrapper.getContentAsByteArray());
        }
        return requestBody;
    }

    /**
     * 获取响应体
     *
     * @param response
     *            响应对象
     * @return 响应体
     */
    private String getResponseBody(HttpServletResponse response) {
        String responseBody = "";
        ContentCachingResponseWrapper wrapper =
            WebUtils.getNativeResponse(response, ContentCachingResponseWrapper.class);
        if (null != wrapper) {
            responseBody = StrUtil.utf8Str(wrapper.getContentAsByteArray());
        }
        return responseBody;
    }

    /**
     * 是否要记录系统日志
     *
     * @param handler
     *            处理器
     * @param request
     *            请求对象
     * @return true 需要记录；false 不需要记录
     */
    private boolean isNeedRecord(Object handler, HttpServletRequest request) {
        // 1、未启用时，不需要记录系统日志
        if (!(handler instanceof HandlerMethod) || Boolean.FALSE.equals(operationLogProperties.getEnabled())) {
            return false;
        }
        // 2、检查是否需要记录内网 IP 操作
        boolean isInnerIp = IpUtils.isInnerIp(JakartaServletUtil.getClientIP(request));
        if (isInnerIp && Boolean.FALSE.equals(operationLogProperties.getIncludeInnerIp())) {
            return false;
        }
        // 3、排除不需要记录系统日志的接口
        HandlerMethod handlerMethod = (HandlerMethod)handler;
        Log methodLog = handlerMethod.getMethodAnnotation(Log.class);
        // 3.1 如果接口方法上既没有 @Log 注解，也没有 @Operation 注解，则不记录系统日志
        Operation methodOperation = handlerMethod.getMethodAnnotation(Operation.class);
        if (null == methodLog && null == methodOperation) {
            return false;
        }
        // 3.2 请求方式不要求记录且接口方法上没有 @Log 注解，则不记录系统日志
        if (null == methodLog && operationLogProperties.getExcludeMethods().contains(request.getMethod())) {
            return false;
        }
        // 3.3 如果接口被隐藏，不记录系统日志
        if (null != methodOperation && methodOperation.hidden()) {
            return false;
        }
        // 3.4 如果接口方法或类上有 @Log 注解，但是要求忽略该接口，则不记录系统日志
        if (null != methodLog && methodLog.ignore()) {
            return false;
        }
        Log classLog = handlerMethod.getBeanType().getDeclaredAnnotation(Log.class);
        return null == classLog || !classLog.ignore();
    }
}