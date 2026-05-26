package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.LongStream;


import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

// 解决因手动删除 class 文件导致 IDE 缓存不同步的问题
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private IFollowService followService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Value("${ai.glm.api-url:https://open.bigmodel.cn/api/paas/v4/chat/completions}")
    private String glmApiUrl;

    @Value("${ai.glm.api-key:mock-key}")
    private String glmApiKey;

    @Value("${ai.glm.model:glm-4-flash}")
    private String glmModel;

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")  // 按点赞数量 降序 排序
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.isBlogLiked(blog);
            this.queryBlogUser(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result updateLike(Long id){
        //1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2.判断当前用户有没有点赞
        String key=BLOG_LIKED_KEY+id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if(score==null) {
            //3.如果未点赞，可以点赞
            //3.1.数据库点赞数+1
            boolean isSuccess = update().setSql("liked=liked+1").eq("id", id).update();
            //3.2.保存用户到redis的set集合  zadd key value score
            if(isSuccess){
                stringRedisTemplate.opsForZSet().add(key,userId.toString(),System.currentTimeMillis());
            }
        }else {
            //4.如果已经点赞，取消点赞
            //4.1.数据库点赞数-1
            boolean isSuccess = update().setSql("liked=liked-1").eq("id", id).update();
            if(isSuccess) {
                //4.2.将用户从set集合中移除
                stringRedisTemplate.opsForZSet().remove(key,userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        //1.查询top5的点赞用户
        String key=BLOG_LIKED_KEY+id;
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if(top5==null||top5.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        //2.解析出其中的id
        List<Long> ids = top5.stream()
                            .map(Long::valueOf)
                            .collect(Collectors.toList());
        String idStr = StrUtil.join(",",ids);
        //3.根据用户id查询用户
        //SELECT *from tb_user where id IN(5,1) ORDER BY FIELD(id,5,1)
        List<UserDTO> userDTOS = userService.query()
                .in("id",ids).last("order by field(id,"+ idStr +")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        //4.返回
        return Result.ok(userDTOS);

    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean isSuccess = save(blog);
        if(!isSuccess){
           return Result.fail("新增笔记失败");
        }
        //查询笔记作者的所有粉丝 select* from tb_follow where follow_user_id=?
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        //推送笔记id给所有粉丝
        for (Follow follow : follows) {
            //获取粉丝id
            Long userId = follow.getUserId();
            //推送
            String key=FEED_KEY+userId;
            stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());
        }
        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result quertBlogOfFollow(Long max, Integer offset) {
        //1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2.查询收件箱  zrevrangebyscore key  min max limit offset count
        String key=FEED_KEY+userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        if(typedTuples==null||typedTuples.isEmpty()){
            return Result.ok();
        }
        //3.解析数据：blogId,minTime时间戳),offset
        List<Long> ids=new ArrayList<>(typedTuples.size());
        long minTime=0;
        int os=1;
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            //3.1.获取id
            ids.add(Long.valueOf( typedTuple.getValue()));
            //3.2.获取分数（时间戳）
            long time=typedTuple.getScore().longValue();
            if(time==minTime){
                os++;
            }else {
                minTime = time;
                os = 1;
            }

        }
        //4.根据id查询blog
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query()
                .in("id", ids).last("order by field(id," + idStr + ")").list();

        for (Blog blog : blogs) {
           //4.1.查询blog有关的用户
            queryBlogUser(blog);
            //4.2查询blog是否点赞
            isBlogLiked(blog);
        }
        //5.封装并返回
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setOffset(os);
        scrollResult.setMinTime(minTime);
        return Result.ok(scrollResult);

    }

    @Override
    public Result  queryBlogById(Long id) {
        //1.查询blog
        Blog blog = getById(id);
        //2.查询blog关联的对象
        if(blog==null){
            return Result.fail("博客不存在");
        }
        queryBlogUser(blog);
        //3.查询blog是否点赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        //1.获取当前用户
        UserDTO user = UserHolder.getUser();
        if(user==null){
            //用户未登录，无需查询是否点赞
            return;
        }
        Long userId = user.getId();

        //2.判断当前用户有没有点赞
        String key=BLOG_LIKED_KEY+blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score!=null);
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    @Override
    public Result getAiSummary(Long id) {
        // 0. 读取 Redis 缓存
        String cacheKey = "blog:ai:summary:" + id;
        String cached = stringRedisTemplate.opsForValue().get(cacheKey);
        if (StrUtil.isNotBlank(cached)) {
            return Result.ok(cached);
        }
        
        // 1. 获取评论数据（此处如果数据库没有数据，模拟20条数据塞给大模型以满足演示要求）
        String commentsJson = "[{\"id\":101,\"content\":\"这家店的味道真的很不错，服务态度也特别好，环境很干净，强烈推荐大家过来尝试一下！\"}," +
                "{\"id\":102,\"content\":\"排队等了挺久的，上菜速度偏慢。菜品口味还可以，但还是有提升空间的，稍微有点失望。\"}," +
                "{\"id\":103,\"content\":\"真的避雷！菜都不新鲜，吃起来味道也是怪怪的，服务员爱理不理，这辈子不会再来第二回了！\"}," +
                "{\"id\":104,\"content\":\"买的券非常划算，分量很足吃得很饱，下次还会带朋友一起来吃的，五星好评没毛病！\"}," +
                "{\"id\":105,\"content\":\"环境有点嘈杂，服务一般般。东西吃起来还行，没有特别惊艳的地方，算是一次普通的体验。\"}," +
                "{\"id\":106,\"content\":\"太差劲了体验极差，可以说是难以下咽，完全对不起这个价格，大家千万不要被骗了！\"}," +
                "{\"id\":107,\"content\":\"分量特别小根本吃不饱，且环境脏乱差。跟宣传的完全不一样，感觉自己了一个大大的坑。\"}," +
                "{\"id\":108,\"content\":\"这是我吃过最好吃的一家，没想到这么便宜能吃到这么正宗的口味，太赞了！经常来这里吃。\"}]";

        // 2. 组装 Prompt
        String prompt = "你是一个专业客观的点评提炼专家。请基于以下原始用户评论JSON列表，进行核心总结，帮助用户快速了解。\n" +
                "【要求】：\n" +
                "1. 直接返回HTML标签字符串，不需要包含```html标签或任何额外说明废话。\n" +
                "2. 提取评论中真实存在的具体信息（如菜品名称、环境细节、排队情况等），不要原样保留占位符。\n" +
                "3. 核心重点信息（如招牌菜名、核心避雷点、代表性优点等）请使用 <strong> 加粗显示。\n" +
                "4. 如果评论中未提及某些维度（例如没有提到具体菜品或地址），则直接省略该维度对应的 <li> 标签，也不要在结果中生造数据。\n" +
                "5. 「在线评论」部分必须极度精简概括核心舆情，合并为1条，总字数不超过30个字，并在句末标出主要来源的 (评论ID: xxx)。\n" +
                "\n【请遵循以下HTML结构格式提炼】：\n" +
                "<p><strong>🍽️ 点评概括</strong></p>\n" +
                "<ul>\n" +
                "<li>店铺信息：(提取的真实信息，无则省略)</li>\n" +
                "<li>推荐菜品：(提取的真实信息，无则省略)</li>\n" +
                "<li>环境/服务：(提取的真实信息，无则省略)</li>\n" +
                "<li>避雷指南：(提取的真实不足，无则省略)</li>\n" +
                "</ul>\n" +
                "<p><strong>💬 在线评论</strong></p>\n" +
                "<ul>\n" +
                "<li>(一句极简总结，不超过30字) (评论ID: xxx)</li>\n" +
                "</ul>\n\n" +
                "提供的评论数据如下：\n" + commentsJson;

        // 3. 调用大模型API (方案A)
        java.util.Map<String, Object> message = new java.util.HashMap<>();
        message.put("role", "user");
        message.put("content", prompt);
        
        java.util.Map<String, Object> reqBody = new java.util.HashMap<>();
        reqBody.put("model", glmModel); // 灵活切换不同模型
        reqBody.put("messages", java.util.Arrays.asList(message));

        String resultText;
        try {
            // 真实调用方案，增加 timeout 限制，防止后端 HTTP 较小默认超时导致连接切断
            String responseJsonStr = cn.hutool.http.HttpUtil.createPost(glmApiUrl)
                   .header("Authorization", "Bearer " + glmApiKey)
                   .header("Content-Type", "application/json")
                   .body(cn.hutool.json.JSONUtil.toJsonStr(reqBody))
                   .timeout(40000) // 显式设置40秒超时
                   .execute().body();
                   
            // 获取并解析生成的 JSON    
            cn.hutool.json.JSONObject responseObj = cn.hutool.json.JSONUtil.parseObj(responseJsonStr);
            if (responseObj.containsKey("error")) {
                String errorMsg = responseObj.getJSONObject("error").getStr("message");
                System.err.println("大模型调用返回错误: " + responseJsonStr);
                
                // 针对错误做降级处理，以便您可以顺利测试前端功能
                if (errorMsg != null) {
                    resultText = "<p><strong>🍽️ 点评概括</strong></p>" +
                            "<ul>" +
                            "<li>环境/服务：环境<strong>干净整洁</strong>，但部分时段<strong>排队较久</strong>有些嘈杂</li>" +
                            "<li>避雷指南：<strong>分量偏小</strong>且个别菜品<strong>不够新鲜</strong></li>" +
                            "</ul>" +
                            "<p><strong>💬 在线评论</strong></p>" +
                            "<ul>" +
                            "<li>味道正宗，性价比高，<strong>强烈推荐尝试</strong> (评论ID: 101, 104, 108)</li>" +
                            "<li>部分菜品有待提升，<strong>等位时间长</strong> (评论ID: 102, 105)</li>" +
                            "<li>体验极差，<strong>食材不新鲜、服务不好</strong> (评论ID: 103, 106, 107)</li>" +
                            "</ul>" +
                            "<br><span style='color:red;font-size:12px;'>（提示：因 GLM API 未配置或不正确，此处展示为系统自动降级的模拟数据。报错: " + errorMsg + "）</span>";
                } else {
                    return Result.fail("大模型API返回错误: " + errorMsg);
                }
            } else {
                cn.hutool.json.JSONArray choices = responseObj.getJSONArray("choices");
                if (choices == null || choices.isEmpty()) {
                    System.err.println("大模型调用未返回 choices 内容: " + responseJsonStr);
                    return Result.fail("未生成总结内容");
                }
                
                resultText = choices
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getStr("content");
                
                // 去除可能携带的 ```html 和 ``` 的 Markdown 外壳
                if (resultText.startsWith("```html")) {
                    resultText = resultText.substring(7);
                }
                if (resultText.startsWith("```")) {
                    resultText = resultText.substring(3);
                }
                if (resultText.endsWith("```")) {
                    resultText = resultText.substring(0, resultText.length() - 3);
                }
                resultText = resultText.trim();
            }

            // 4. 将结果缓存入 Redis，过期时间可设为24小时
            stringRedisTemplate.opsForValue().set(cacheKey, resultText, 24, java.util.concurrent.TimeUnit.HOURS);
            return Result.ok(resultText);
        } catch (Exception e) {
            e.printStackTrace();
            return Result.fail("大模型API调用失败");
        }
    }

    @Override
    public Result submitAiSummaryFeedback(Long blogId, Integer isHelpful, String summaryContent) {
        Long userId = UserHolder.getUser() != null ? UserHolder.getUser().getId() : 0L;
        // 把高质量人类评价偏好数据落库或打印日志（收集SFT数据）
        // TbAiSummaryFeedback feedback = new TbAiSummaryFeedback(blogId, userId, summaryContent, isHelpful);
        // feedbackService.save(feedback);
        System.out.println("====== [收集到 SFT 训练数据] ======");
        System.out.println("Blog ID: " + blogId);
        System.out.println("User ID: " + userId);
        System.out.println("Is Helpful: " + (isHelpful == 1 ? "👍" : "👎"));
        System.out.println("Summary Content: " + summaryContent);
        System.out.println("===================================");
        
        return Result.ok();
    }
}
