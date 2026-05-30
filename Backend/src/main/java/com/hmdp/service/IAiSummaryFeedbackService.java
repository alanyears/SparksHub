package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.entity.AiSummaryFeedback;

public interface IAiSummaryFeedbackService extends IService<AiSummaryFeedback> {

    void saveFeedback(Long blogId, Long userId, Integer isHelpful, String summaryContent);

    Result exportForSft();
}
