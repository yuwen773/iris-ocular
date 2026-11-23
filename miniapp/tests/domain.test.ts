import { describe, expect, it } from 'vitest'
import {
  abstainReasonLabel,
  confidenceBand,
  outcomeOf,
  severityLabel,
  severityRisk,
} from '@/lib/domain'

describe('判读结果口径', () => {
  it('拒识优先于待复核，待复核优先于不作患者结论', () => {
    expect(outcomeOf({ aiRejected: true, requiresReview: true, patientConclusionAllowed: false })).toBe('rejected')
    expect(outcomeOf({ aiRejected: false, requiresReview: true, patientConclusionAllowed: false })).toBe('review')
    expect(outcomeOf({ requiresReview: false, patientConclusionAllowed: false })).toBe('blocked')
    expect(outcomeOf({})).toBe('classified')
  })

  it('严重度标签大小写不敏感，认不出时原样返回', () => {
    expect(severityLabel('HIGH')).toBe('高风险')
    expect(severityLabel('moderate')).toBe('中度')
    expect(severityLabel('罕见值')).toBe('罕见值')
    expect(severityLabel(null)).toBe('未评估')
  })

  it('严重度到风险档位的映射', () => {
    expect(severityRisk('mild')).toBe('attention')
    expect(severityRisk('SEVERE')).toBe('critical')
    expect(severityRisk(undefined)).toBe('unknown')
  })

  it('置信度按 0.75 / 0.6 分档，百分数也认', () => {
    expect(confidenceBand(0.9).label).toBe('可信')
    expect(confidenceBand(70).label).toBe('偏低')
    expect(confidenceBand(0.2).label).toBe('低')
    expect(confidenceBand(null).label).toBe('未评估')
  })

  it('拒识原因码转成人话，未知码不吞', () => {
    expect(abstainReasonLabel('ABSTAIN_QUALITY_FAIL')).toContain('影像质量')
    expect(abstainReasonLabel('SOME_NEW_CODE')).toBe('SOME_NEW_CODE')
    expect(abstainReasonLabel(null)).toContain('暂无法提供结果')
  })
})
