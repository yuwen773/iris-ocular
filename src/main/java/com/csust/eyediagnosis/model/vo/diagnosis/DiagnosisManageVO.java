package com.csust.eyediagnosis.model.vo.diagnosis;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.csust.eyediagnosis.model.entity.Diagnosis;
import com.csust.eyediagnosis.service.DiagnosisEvidence;
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
     * 原始 softmax top-1 百分数（0–100）。V34 之前写入的记录只有这一列。
     *
     * <p>与 {@link #classificationProbability} 是同一件事的两种刻度：老行没有分类概率
     * 列时，列表读数退到它，仍按「模型输出概率（未标定）」标注。它不是置信度，
     * 更不是诊断可靠性。
     */
    private Double probability;

    /**
     * 本次判读能否对患者呈现结论（V34 的证据列，老记录为 null）。
     *
     * <p>列表必须带上它：概率读数的标签由校准状态与这一道门共同决定，缺字段就会
     * 让「校准过但不许作患者结论」的记录在列表里叫「校准后概率」、在记录纸上叫
     * 「模型输出概率（不作患者结论）」。
     */
    private Boolean patientConclusionAllowed;

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
        diagnosisVO.setConfidence(DiagnosisEvidence.exposedConfidence(
                diagnosis.getCalibrationStatus(), diagnosis.getCalibratedProbability()));
        diagnosisVO.setRequiresReview("AWAITING_REVIEW".equals(diagnosis.getHumanReviewStatus()));
        if (Boolean.TRUE.equals(diagnosis.getAiRejected())) {
            diagnosisVO.clearAiConclusion();
        }
        return diagnosisVO;
    }

    /**
     * 拒识时清掉「结论」，但保留概率证据。
     *
     * <p>与 {@link DiagnosisResultVO#clearAiConclusion()} 同一口径：模型输出概率是
     * 「模型说了什么」的原始记录，不是诊断结论；一边清一边留，会让同一条记录
     * 在列表里没有读数、在详情里却有。
     */
    public void clearAiConclusion() {
        result = null;
        confidence = null;
        severity = null;
    }

}


