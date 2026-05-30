package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.AiSummaryFeedback;
import com.hmdp.mapper.AiSummaryFeedbackMapper;
import com.hmdp.service.IAiSummaryFeedbackService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class AiSummaryFeedbackServiceImpl extends ServiceImpl<AiSummaryFeedbackMapper, AiSummaryFeedback>
        implements IAiSummaryFeedbackService {

    @Override
    public void saveFeedback(Long blogId, Long userId, Integer isHelpful, String summaryContent) {
        AiSummaryFeedback feedback = new AiSummaryFeedback();
        feedback.setBlogId(blogId);
        feedback.setUserId(userId != null ? userId : 0L);
        feedback.setIsHelpful(isHelpful);
        feedback.setSummaryContent(summaryContent);
        feedback.setCreateTime(LocalDateTime.now());
        save(feedback);
    }

    @Override
    public Result exportForSft() {
        List<AiSummaryFeedback> rows = query().orderByAsc("id").list();
        List<Map<String, Object>> dataset = rows.stream().map(row -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.getId());
            item.put("blog_id", row.getBlogId());
            item.put("user_id", row.getUserId());
            item.put("is_helpful", row.getIsHelpful());
            item.put("label", row.getIsHelpful() != null && row.getIsHelpful() == 1 ? "helpful" : "not_helpful");
            item.put("summary_content", row.getSummaryContent());
            item.put("create_time", row.getCreateTime());
            return item;
        }).collect(Collectors.toList());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("total", dataset.size());
        payload.put("records", dataset);
        return Result.ok(payload);
    }
}
