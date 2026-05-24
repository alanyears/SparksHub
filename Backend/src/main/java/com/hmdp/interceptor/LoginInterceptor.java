package com.hmdp.interceptor;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class LoginInterceptor implements HandlerInterceptor {
    // RefreshTokenInterceptor比LoginInterceptor先运行

    // HandlerInterceptor实现类可创建3个方法（按Ctrl+i）,分别是preHandle()、postHandle()、afterCompletion()
    /**
     * 执行顺序：preHandle() → Controller → postHandle() → 视图渲染 → afterCompletion()
     *
     * 1 preHandle()：
     * 执行时机：在 Controller 方法执行之前
     * 用途：登录状态判断等
     * 返回值：true 继续执行，false 中断
     *
     * 2 postHandle()：
     * 执行时机：在 Controller 方法执行之后，视图渲染之前
     *
     * 3 afterCompletion()：
     * 执行时机：在视图渲染完成之后，即请求处理完全结束。（视图渲染：一般是前端接收后端数据进行渲染，生成HTML页面）
     * 用途：日志记录等
     * 特点：无论是否抛出异常都会执行
     */

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
       //1.判断是否需要拦截（ThreadLocal）中是否有用户
        if(UserHolder.getUser()==null){
            //需要拦截，设置状态码
            response.setStatus(401);
            //拦截
            return false; // 返回给 Spring MVC 的拦截器处理
        }
        //由用户放行
        return true;
    }


}
