package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.BlogComments;
import com.hmdp.entity.Follow;
import com.hmdp.entity.Shop;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IAiSummaryFeedbackService;
import com.hmdp.service.IBlogCommentsService;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IShopService;
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

    @Resource
    private IBlogCommentsService blogCommentsService;

    @Resource
    private IAiSummaryFeedbackService aiSummaryFeedbackService;

    @Resource
    private IShopService shopService;

    private static final String AI_SUMMARY_CACHE_KEY = "blog:ai:summary:v2:";

    @Value("${ai.glm.api-url:https://open.bigmodel.cn/api/paas/v4/chat/completions}")
    private String glmApiUrl;

    @Value("${ai.glm.api-key:mock-key}")
    private String glmApiKey;

    @Value("${ai.glm.model:glm-4-flash}")
    private String glmModel;

    @Value("${ai.dify.api-url:http://localhost/v1/workflows/run}")
    private String difyApiUrl;

    @Value("${ai.dify.api-key:app-lSgOkO9nGkJOmTdAXH5mz666}")
    private String difyApiKey;

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
        String cacheKey = AI_SUMMARY_CACHE_KEY + id;
        String cached = stringRedisTemplate.opsForValue().get(cacheKey);
        if (StrUtil.isNotBlank(cached)) {
            return Result.ok(cached);
        }

        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("博客不存在");
        }
        List<BlogComments> commentList = listBlogComments(id);
        
        // 添加调试日志
        System.out.println("=== AI总结调试信息 ===");
        System.out.println("博客ID: " + id);
        System.out.println("博客标题: " + blog.getTitle());
        System.out.println("店铺ID: " + blog.getShopId());
        System.out.println("评论数量: " + commentList.size());
        System.out.println("评论列表: " + commentList);
        
        String commentsJson = buildAiContextJson(blog, commentList);
        String shopName = resolveShopName(blog.getShopId());
        int commentCount = commentList.size();
        
        System.out.println("传递给Dify的JSON: " + commentsJson);
        System.out.println("店铺名称: " + shopName);
        System.out.println("=====================");

        java.util.Map<String, Object> reqBody = new java.util.HashMap<>();
        java.util.Map<String, Object> inputs = new java.util.HashMap<>();
        // 与 Dify「开始」节点变量名一致：blogId、commentsJson、shopName、commentCount
        inputs.put("blogId", id);
        inputs.put("commentsJson", commentsJson);
        inputs.put("shopName", shopName);
        inputs.put("commentCount", commentCount);

        reqBody.put("inputs", inputs);
        reqBody.put("response_mode", "blocking");
        String userId = UserHolder.getUser() != null ? String.valueOf(UserHolder.getUser().getId()) : "anonymous";
        reqBody.put("user", "user_" + userId);

        String resultText;
        try {
            // 3. 调用 Dify API
            String responseJsonStr = cn.hutool.http.HttpUtil.createPost(difyApiUrl)
                   .header("Authorization", "Bearer " + difyApiKey)
                   .header("Content-Type", "application/json")
                   .body(cn.hutool.json.JSONUtil.toJsonStr(reqBody))
                   .timeout(40000) 
                   .execute().body();

            cn.hutool.json.JSONObject responseObj = cn.hutool.json.JSONUtil.parseObj(responseJsonStr);
            if (responseObj.containsKey("code") || responseObj.containsKey("error") || !responseObj.containsKey("data")) {
                System.err.println("Dify调用错误: " + responseJsonStr);
                resultText = buildFallbackSummaryHtml(blog, commentsJson);
            } else {
                cn.hutool.json.JSONObject data = responseObj.getJSONObject("data");
                cn.hutool.json.JSONObject outputs = data.getJSONObject("outputs");
                
                // Dify 的 outputs key 是在工作流配置的，尝试获取常见的值
                if (outputs.containsKey("result")) {
                    resultText = outputs.getStr("result");
                } else if (outputs.containsKey("text")) {
                    resultText = outputs.getStr("text");
                } else {
                    // 取第一个 output 字段
                    resultText = outputs.values().iterator().next().toString();
                }

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

            // 4. 将结果缓存入 Redis
            stringRedisTemplate.opsForValue().set(cacheKey, resultText, 24, java.util.concurrent.TimeUnit.HOURS);
            return Result.ok(resultText);
        } catch (Exception e) {
            e.printStackTrace();
            return Result.fail("Dify API调用失败");
        }
    }

    @Override
    public Result submitAiSummaryFeedback(Long blogId, Integer isHelpful, String summaryContent) {
        Long userId = UserHolder.getUser() != null ? UserHolder.getUser().getId() : 0L;
        aiSummaryFeedbackService.saveFeedback(blogId, userId, isHelpful, summaryContent);
        return Result.ok();
    }

    @Override
    public Result exportAiSummaryFeedback() {
        return aiSummaryFeedbackService.exportForSft();
    }

    private List<BlogComments> listBlogComments(Long blogId) {
        return blogCommentsService.query()
                .eq("blog_id", blogId)
                .apply("(status IS NULL OR status = 0)")
                .orderByDesc("create_time")
                .last("LIMIT 500")
                .list();
    }

    private String resolveShopName(Long shopId) {
        if (shopId == null) {
            return "";
        }
        Shop shop = shopService.getById(shopId);
        return shop != null ? StrUtil.blankToDefault(shop.getName(), "") : "";
    }

    /** 合并点评笔记与评论，供 Dify commentsJson 单字段解析 */
    private String buildAiContextJson(Blog blog, List<BlogComments> commentList) {
        cn.hutool.json.JSONObject root = new cn.hutool.json.JSONObject();
        cn.hutool.json.JSONObject blogNote = new cn.hutool.json.JSONObject();
        blogNote.set("id", blog.getId());
        blogNote.set("title", blog.getTitle());
        blogNote.set("content", stripHtml(blog.getContent()));
        root.set("blogNote", blogNote);
        cn.hutool.json.JSONArray comments = new cn.hutool.json.JSONArray();
        for (BlogComments c : commentList) {
            cn.hutool.json.JSONObject item = new cn.hutool.json.JSONObject();
            item.set("id", c.getId());
            item.set("content", c.getContent());
            comments.add(item);
        }
        root.set("comments", comments);
        return root.toString();
    }

    private String stripHtml(String html) {
        if (StrUtil.isBlank(html)) {
            return "";
        }
        return html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }

    private String buildFallbackSummaryHtml(Blog blog, String commentsJson) {
        StringBuilder sb = new StringBuilder();
        cn.hutool.json.JSONObject root = cn.hutool.json.JSONUtil.parseObj(commentsJson);
        cn.hutool.json.JSONObject blogNote = root.getJSONObject("blogNote");
        String title = blogNote != null ? blogNote.getStr("title") : blog.getTitle();
        String content = blogNote != null ? blogNote.getStr("content") : stripHtml(blog.getContent());
        sb.append("<p><strong>点评笔记</strong></p><p>")
                .append(StrUtil.blankToDefault(title, "无标题"))
                .append("</p><p>")
                .append(StrUtil.sub(content, 0, 500))
                .append(content != null && content.length() > 500 ? "…" : "")
                .append("</p>");
        cn.hutool.json.JSONArray comments = root.getJSONArray("comments");
        sb.append("<p><strong>在线评论</strong></p>");
        if (comments == null || comments.isEmpty()) {
            sb.append("<p>暂无在线评论。</p>");
        } else {
            sb.append("<ul>");
            int limit = Math.min(comments.size(), 8);
            for (int i = 0; i < limit; i++) {
                cn.hutool.json.JSONObject c = comments.getJSONObject(i);
                sb.append("<li>").append(c.getStr("content"))
                        .append(" (评论ID: ").append(c.getLong("id")).append(")</li>");
            }
            sb.append("</ul>");
        }
        sb.append("<br><span style='color:red;font-size:12px;'>（Dify 未就绪，以上为数据库原文摘要）</span>");
        return sb.toString();
    }
}