package com.patrick.wrench.api;

import com.patrick.wrench.rate.limiter.types.annotations.RateLimiterAccessInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author Fuzhengwei bugstack.cn @小傅哥
 * 2025-05-07 14:41
 */
@Slf4j
@RestController()
@CrossOrigin("*")
@RequestMapping("/api/v1/index/")
public class IndexController {

    /**
     * curl --request GET --url 'http://127.0.0.1:9191/api/v1/index/draw?userId=xiaofuge'
     */
    @RateLimiterAccessInterceptor(key = "userId", fallbackMethod = "drawErrorRateLimiter", permitsPerSecond = 1.0d, blackListCount = 1)
    @RequestMapping(value = "draw", method = RequestMethod.GET)
    public String draw(String userId) {
        return "test";
    }

    public String drawErrorRateLimiter(String userId) {
        return "rateLimiter";
    }

}
