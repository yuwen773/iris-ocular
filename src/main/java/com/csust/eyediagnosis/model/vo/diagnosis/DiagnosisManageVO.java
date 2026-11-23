package com.csust.eyediagnosis.model.vo.diagnosis;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.csust.eyediagnosis.model.entity.Diagnosis;
import lombok.Data;
import org.springframework.beans.BeanUtils;

import java.io.Serializable;
import java.util.Date;

import static com.csust.eyediagnosis.constant.StatusConstant.COMPLETED;


/**
 * @ClassNameDiagnosisVO
 * @Description diagnosis脱敏对象
 * @Author钰玟
 * @Date2025/4/21 下午8:47
 * @Version 1.0
 **/
@Data
public class DiagnosisManageVO implements Serializable{
    /**
     * id
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 图片 url
     */
    private String url;

    /**
     * 创建用户名称
     */
    private String username;

    /**
     * 诊断结果
     */
    private String result;

    /**
     * 诊断置信度：**只有真正校准过才有值**（{@code calibrationStatus = CALIBRATED}）。
     *
     * <p>库里的同名列存的是「模型自评概率百分数」（分析工作台口径），未标定时
     * 把它当置信度展示，等于让列表里的一格数字冒充医学可靠性。未标定时这里为
     * {@code null}，读数请改用 {@link #classificationProbability} 并如实改标签。
     */
    private Double confidence;

    /** 校准状态（CALIBRATED / NOT_CALIBRATED）；老记录为 null，按「未标定」处理。 */
    private String calibrationStatus;

    /** 原始 softmax top-1（0–1）。模型输出强度，不是校准概率，也不是可靠性。 */
    private Double classificationProbability;

    /**
     * 严重程度
     */
    private String severity;
    /**
     * 复审状态 1, 未复审 2, 复审中 3, 已复审
     */
    private int reexamineStatus;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 创建时间
     */
    private String status = COMPLETED ;

    /** AI 是否拒识 */
    private Boolean aiRejected;
    /** 拒识原因 */
    private String abstainReason;
    /** 是否需要人工复核 */
    private Boolean requiresReview;

    private static final long serialVersionUID = 1L;


    /**
     * 封装类转对象
     */
    public static Diagnosis voToObj(DiagnosisManageVO diagnosisVO) {
        if (diagnosisVO == null) {
            return null;
        }
        Diagnosis diagnosis = new Diagnosis();
        BeanUtils.copyProperties(diagnosisVO, diagnosis);
        return diagnosis;
    }

    /**
     * 对象转封装类
     */
    public static DiagnosisManageVO objToVo(Diagnosis diagnosis) {
        if (diagnosis == null) {
            return null;
        }
        DiagnosisManageVO diagnosisVO = new DiagnosisManageVO();
        BeanUtils.copyProperties(diagnosis, diagnosisVO);
        // 与写入路径同一口径（见 DiagnosisResultVO / DiagnosisServiceImpl.buildDiagnosisResult）：
        // 未标定时不把模型自评概率挂在 confidence 上。
        diagnosisVO.setConfidence("CALIBRATED".equals(diagnosis.getCalibrationStatus())
                ? diagnosis.getCalibratedProbability() : null);
        diagnosisVO.setRequiresReview("AWAITING_REVIEW".equals(diagnosis.getHumanReviewStatus()));
        if (Boolean.TRUE.equals(diagnosis.getAiRejected())) {
            diagnosisVO.clearAiConclusion();
        }
        return diagnosisVO;
    }

    public void clearAiConclusion() {
        result = null;
        confidence = null;
        classificationProbability = null;
        severity = null;
    }

}


