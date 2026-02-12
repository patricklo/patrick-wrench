package com.patrick.wrench.rate.limiter.aop;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.util.concurrent.RateLimiter;
import com.patrick.wrench.rate.limiter.types.annotations.RateLimiterAccessInterceptor;
import org.apache.commons.lang.StringUtils;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

@Aspect
public class RateLimiterAOP {
    private final Logger log = LoggerFactory.getLogger(RateLimiterAOP.class);

    @Value("{config.rate.limiter.switch:open}")
    private String rateLimiterSwitch;

    //个人限频记录1秒钟
    private final Cache<String, RateLimiter> loginRecord = CacheBuilder.newBuilder().expireAfterWrite(1, TimeUnit.SECONDS).build();

    //个人限频黑名单24h - 分布式业务场景，可以记录到redis中
    private final Cache<String, Long> blackList = CacheBuilder.newBuilder().expireAfterWrite(24, TimeUnit.HOURS).build();

    @Pointcut("@annotation(com.patrick.wrench.rate.limiter.types.annotations.RateLimiterAccessInterceptor)")
    public void aopPoint(){

    }

    @Around("aopPoint() && @annotation(rateLimiterAccessInterceptor)")
    public Object doRouter(ProceedingJoinPoint jp, RateLimiterAccessInterceptor rateLimiterAccessInterceptor) throws Throwable{
        // 0. 限流开关【open /close】
        if (StringUtils.isBlank(rateLimiterSwitch) || "close".equalsIgnoreCase(rateLimiterSwitch)) {
            return jp.proceed();
        }

        String key = rateLimiterAccessInterceptor.key();
        if (StringUtils.isBlank(key)){
            throw new RuntimeException("annotation RateLimiter uId is null");
        }

        //获取拦截字段
        String keyAttr = getAttrValue(key, jp.getArgs());
        log.info("aop attr {}", keyAttr);

        // 黑名单拦截
        if (!"all".equalsIgnoreCase(keyAttr)
                && rateLimiterAccessInterceptor.blackListCount() != 0
                && null != blackList.getIfPresent(keyAttr)
                && blackList.getIfPresent(keyAttr) > rateLimiterAccessInterceptor.blackListCount()
        ) {
            log.info("限流-黑名单拦截（24h）:{}", keyAttr);
            return fallbackMethodResult(jp, rateLimiterAccessInterceptor.fallbackMethod());
        }

        //获取限流 -》 Guava 缓存1分钟
        RateLimiter rateLimiter = loginRecord.getIfPresent(keyAttr);
        if (null == rateLimiter) {
            rateLimiter = RateLimiter.create(rateLimiterAccessInterceptor.permitsPerSecond());
            loginRecord.put(keyAttr, rateLimiter);
        }

        //限流拦截
        if (!rateLimiter.tryAcquire()) {
            if (rateLimiterAccessInterceptor.blackListCount() != 0) {
                if (null == blackList.getIfPresent(keyAttr)){
                    blackList.put(keyAttr, 1l);
                } else {
                    blackList.put(keyAttr, blackList.getIfPresent(keyAttr) + 1l);
                }
            }
            log.info("限流-超频次拦截:{}", keyAttr);
            return fallbackMethodResult(jp, rateLimiterAccessInterceptor.fallbackMethod());
        }

        //返回处理
        return jp.proceed();

    }

    private Object fallbackMethodResult(JoinPoint jp, String fallbackMethod) throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Signature sig = jp.getSignature();
        MethodSignature methodSignature = (MethodSignature) sig;
        Method method = jp.getTarget().getClass().getMethod(fallbackMethod, methodSignature.getParameterTypes());
        return method.invoke(jp.getThis(), jp.getArgs());
    }

    public String getAttrValue(String attr, Object[] args) {
        if (args[0] instanceof String) {
            return args[0].toString();
        }
        String fieldValue = null;
        for (Object arg : args) {
            try {
                if (StringUtils.isNotBlank(fieldValue)) {
                    break;
                }

                // fieldvalue = BeanUtils.getProperty(arg,attr);
                //fix: 使用lombok时，uId这种字段的get方法与Idea生成的get方法不同，会导致获取不到属性值 ，因此改成反射方法
                fieldValue = String.valueOf(this.getValueByName(arg, attr));
            } catch (Exception e) {
                log.error("获取路由属性值失败 attr:{}", attr, e);
            }
        }
        return fieldValue;
    }

    private Object getValueByName(Object item, String name) {
        try {
            Field field = getFileByName(item, name);
            if (field == null) {
                return null;
            }
            field.setAccessible(true);
            Object o = field.get(item);
            field.setAccessible(false);
            return o;
        } catch (IllegalAccessException e){
            return null;
        }
    }

    private Field getFileByName(Object item, String name) {
        try {
            Field field;
            try {
                field = item.getClass().getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                field = item.getClass().getSuperclass().getDeclaredField(name);
            }
            return field;
        } catch (NoSuchFieldException e) {
            return null;
        }
    }


}
