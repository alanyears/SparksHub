package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.entity.BlogComments;
import com.hmdp.service.IBlogCommentsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/blog-comments")
public class BlogCommentsController {

    @Resource
    private IBlogCommentsService blogCommentsService;

    /**
     * 根据博客ID查询评论列表
     */
    @GetMapping
    public Result queryByBlogId(@RequestParam("blogId") Long blogId) {
        List<BlogComments> comments = blogCommentsService.query()
                .eq("blog_id", blogId)
                .apply("(status IS NULL OR status = 0)")
                .orderByDesc("create_time")
                .list();
        return Result.ok(comments);
    }
}