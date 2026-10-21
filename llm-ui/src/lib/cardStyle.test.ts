import { describe, expect, it } from 'vitest'

import {
  FAMILY_STYLE,
  METER_FILL,
  PRIORITY_TONE,
  WEIGHT_STYLE,
  isExploration,
  layoutFamily,
  meterCaption,
  meterLevel,
  scoreReadout,
  weightClass,
} from './cardStyle'
import { EVIDENCE_LABELS, type EvidenceGrade } from './curation'
import type { PostKind } from '../db/types'

const kindPost = (kind: PostKind) => ({ kind })

describe('cards are told apart by what they are made of', () => {
  it('maps the four kinds onto three layout families', () => {
    expect(layoutFamily(kindPost('sourced'))).toBe('sourced')
    expect(layoutFamily(kindPost('synthetic_explainer'))).toBe('knowledge')
    expect(layoutFamily(kindPost('fiction'))).toBe('narrative')
    expect(layoutFamily(kindPost('serial'))).toBe('narrative')
  })

  it('gives every family its own rail colour, so none share by accident', () => {
    const rails = Object.values(FAMILY_STYLE).map((style) => style.rail)
    expect(new Set(rails).size).toBe(rails.length)
  })
})

describe('priority decides how much the card stops you', () => {
  it('emphasises high and mutes low', () => {
    expect(weightClass('high')).toBe('emphasised')
    expect(weightClass('low')).toBe('muted')
    expect(weightClass('medium')).toBe('normal')
  })

  it('leaves a pre-B09 post at normal instead of guessing urgency from its score', () => {
    expect(weightClass(undefined)).toBe('normal')
  })

  it('has a title colour per weight class', () => {
    expect(Object.keys(WEIGHT_STYLE).sort()).toEqual(['emphasised', 'muted', 'normal'])
    expect(WEIGHT_STYLE.emphasised.title).not.toBe(WEIGHT_STYLE.muted.title)
  })
})

describe('evidence meter', () => {
  it('fills three ticks only for human evidence', () => {
    expect(meterLevel('systematic_review')).toBe(3)
    expect(meterLevel('human_study')).toBe(3)
  })

  it('draws no tick for fiction: no evidence is not weak evidence', () => {
    expect(meterLevel('not_applicable')).toBe(0)
    expect(meterLevel(undefined)).toBe(0)
  })

  it('ranks mechanism above anecdote and marketing', () => {
    expect(meterLevel('mechanism')).toBe(2)
    expect(meterLevel('animal_study')).toBe(2)
    expect(meterLevel('anecdote')).toBe(1)
    expect(meterLevel('marketing')).toBe(1)
    expect(meterLevel('ungraded')).toBe(1)
  })

  it('never paints a weak grade with the verified colour', () => {
    expect(METER_FILL.trustworthy).toContain('emerald')
    expect(METER_FILL.weak).not.toContain('emerald')
    expect(METER_FILL.mixed).not.toContain('emerald')
  })

  it('says the grade in Chinese and leaks no internal enum value', () => {
    const grades = Object.keys(EVIDENCE_LABELS) as EvidenceGrade[]
    for (const grade of grades) {
      expect(meterCaption(grade)).not.toMatch(/[a-z_]/)
    }
    expect(meterCaption('marketing')).toBe('证据档：营销说法')
    expect(meterCaption('not_applicable')).toContain('不涉及证据')
    expect(meterCaption(undefined)).toContain('未分级')
  })
})

describe('the computed verdict stays visible', () => {
  it('shows quality to two decimals', () => {
    expect(scoreReadout({ qualityScore: 0.5 })).toBe('0.50')
    expect(scoreReadout({ qualityScore: 0.625 })).toBe('0.63')
  })

  it('only reads the tag the server stamps, never the kind', () => {
    expect(isExploration({ tags: ['explore'] })).toBe(true)
    expect(isExploration({ tags: [] })).toBe(false)
  })

  it('has a chip tone for every priority', () => {
    expect(Object.keys(PRIORITY_TONE).sort()).toEqual(['high', 'low', 'medium'])
  })
})
