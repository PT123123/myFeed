/**
 * Prompt studio (docs/06 §5, docs/08 §5).
 *
 * The reader asked for the prompts to be changeable at any time — but three of
 * the four things that make a batch are not prompts, they are the contract the
 * validator checks. So this page edits the style block, the persona wiring and
 * the angle pool, and shows the locked parts read-only beside them.
 *
 * The preview is the point of the page: it renders the real prompt for a
 * throwaway plan without calling the provider, so a bad edit is visible here
 * instead of in the next paid batch.
 */

import { useCallback, useEffect, useMemo, useState } from 'react'
import { Eye, RotateCcw, Save } from 'lucide-react'

import { NoticeBar } from '../components/NoticeBar'
import {
  chunkLines,
  chunkWarnings,
  diffChunk,
  isChunkModified,
  previewStats,
  type PromptChunkKey,
} from '../lib/prompts'
import { ApiError, getPromptSettings, previewPrompt, putPromptSettings } from '../services/api'
import type { PromptPreviewDto, PromptSettingsDto } from '../services/types'

type Drafts = Record<PromptChunkKey, string>

const EMPTY_DRAFTS: Drafts = { style_rules: '', persona_notes: '', angles: '' }

function draftsOf(data: PromptSettingsDto): Drafts {
  const next = { ...EMPTY_DRAFTS }
  for (const chunk of data.chunks) next[chunk.key] = chunk.text
  return next
}

export function PromptsPage() {
  const [data, setData] = useState<PromptSettingsDto | null>(null)
  const [drafts, setDrafts] = useState<Drafts>(EMPTY_DRAFTS)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [notice, setNotice] = useState<{ tone: 'info' | 'good' | 'warn'; message: string } | null>(
    null,
  )
  const [busy, setBusy] = useState(false)
  const [preview, setPreview] = useState<PromptPreviewDto | null>(null)

  const load = useCallback(async () => {
    setLoadError(null)
    try {
      const next = await getPromptSettings()
      setData(next)
      setDrafts(draftsOf(next))
    } catch (err) {
      setLoadError(err instanceof ApiError ? `${err.code}：${err.message}` : '读取失败')
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  const chunkByKey = useMemo(() => {
    const map = new Map<PromptChunkKey, PromptSettingsDto['chunks'][number]>()
    for (const chunk of data?.chunks ?? []) map.set(chunk.key, chunk)
    return map
  }, [data])

  if (loadError) {
    return (
      <section className="paf-card space-y-2" data-page="prompts" data-state="unreachable">
        <h1 className="paf-section-title">提示词</h1>
        <p className="text-sm text-[var(--paf-muted)]">
          连不上服务端，看不到当前提示词：{loadError}
        </p>
        <button
          type="button"
          className="paf-btn border border-[var(--paf-border)]"
          onClick={() => void load()}
        >
          重新读取
        </button>
      </section>
    )
  }

  if (!data) {
    return (
      <section className="paf-card text-sm text-[var(--paf-muted)]" data-page="prompts">
        读取提示词设置…
      </section>
    )
  }

  const edited = (key: PromptChunkKey) => drafts[key] !== (chunkByKey.get(key)?.text ?? '')
  const dirtyCount = (Object.keys(drafts) as PromptChunkKey[]).filter(edited).length

  const onSave = async () => {
    setBusy(true)
    try {
      const saved = await putPromptSettings({
        style_rules: drafts.style_rules,
        persona_notes: drafts.persona_notes,
        angles: drafts.angles,
      })
      setData(saved)
      setDrafts(draftsOf(saved))
      setPreview(null)
      const angles = saved.chunks.find((c) => c.key === 'angles')
      setNotice({
        tone: 'good',
        message: `已保存。下一批按这里的写法、人格接法与 ${chunkLines(angles?.text ?? '').length} 条角度生成。已经排队的批次会沿用它规划好的槽位，不会中途换风格。`,
      })
    } catch (err) {
      setNotice({
        tone: 'warn',
        message: err instanceof ApiError ? `保存失败：${err.code} ${err.message}` : '保存失败',
      })
    } finally {
      setBusy(false)
    }
  }

  const onPreview = async () => {
    setBusy(true)
    try {
      // The preview uses what is stored, so unsaved edits are warned about above.
      setPreview(await previewPrompt(5))
      setNotice(
        dirtyCount > 0
          ? {
              tone: 'info',
              message: `预览的是已保存的版本：还有 ${dirtyCount} 块没保存。先保存再预览才会带上你的改动。`,
            }
          : { tone: 'info', message: '这是下一批实际要发送的完整提示词，没有调用模型，不产生费用。' },
      )
    } catch (err) {
      setNotice({
        tone: 'warn',
        message: err instanceof ApiError ? `预览失败：${err.code} ${err.message}` : '预览失败',
      })
    } finally {
      setBusy(false)
    }
  }

  const stats = preview ? previewStats(preview.prompt) : null

  return (
    <div className="space-y-3" data-page="prompts">
      <section className="paf-card space-y-2">
        <h1 className="paf-section-title">提示词</h1>
        <p className="text-sm text-[var(--paf-muted)]">
          正文的口气、署名人怎么接进正文、选题角度，都在这里改。改完先用「预览实际提示词」看一眼，再等下一批。
        </p>
        <p className="text-sm text-[var(--paf-muted)]">
          输出格式、候选集硬约束、槽位清单与兴趣画像这四部分是锁死的：它们决定了服务端能不能收下模型的回答，也决定了你的隐私边界。
        </p>
        <p className="text-sm text-[var(--paf-muted)]" data-defaults-source={data.defaults_source}>
          点「恢复默认」会回到这一套措辞：{data.defaults_source}。你自己那份放在服务器本机的
          <code className="px-1">data/prompts-personal.json</code>，不在版本库里。
        </p>
      </section>

      {data.chunks.map((chunk) => {
        const text = drafts[chunk.key]
        const diff = diffChunk(text, chunk.default_text)
        const modified = isChunkModified(text, chunk.default_text)
        const warnings = chunkWarnings(chunk.key, text)
        return (
          <section
            key={chunk.key}
            className="paf-card space-y-2"
            data-chunk={chunk.key}
            data-modified={modified ? 'true' : 'false'}
            data-unsaved={edited(chunk.key) ? 'true' : 'false'}
          >
            <div className="flex items-baseline justify-between gap-3">
              <h2 className="paf-section-title">{chunk.title}</h2>
              {modified ? (
                <span className="paf-chip-tight border-[var(--paf-accent)] text-[var(--paf-accent)]">
                  已改
                </span>
              ) : null}
            </div>
            <p className="text-xs text-[var(--paf-muted)]">{chunk.hint}</p>
            <textarea
              className="paf-input w-full resize-y font-mono text-xs leading-relaxed"
              rows={chunk.key === 'angles' ? 10 : 8}
              value={text}
              spellCheck={false}
              aria-label={chunk.title}
              onChange={(e) => setDrafts((d) => ({ ...d, [chunk.key]: e.target.value }))}
            />
            {warnings.length > 0 ? (
              <ul className="space-y-1 text-xs text-amber-600 dark:text-amber-400">
                {warnings.map((w) => (
                  <li key={w}>{w}</li>
                ))}
              </ul>
            ) : null}
            <div className="flex flex-wrap items-center justify-between gap-2 border-t border-[var(--paf-border)] pt-2">
              <p className="text-xs text-[var(--paf-muted)]" data-diff-added={diff.added.length}
                data-diff-removed={diff.removed.length}>
                与默认写法相比：新增 {diff.added.length} 行，删掉 {diff.removed.length} 行，保留 {diff.kept} 行 ·
                当前 {text.length} 字
              </p>
              <button
                type="button"
                className="paf-btn border border-[var(--paf-border)]"
                onClick={() => setDrafts((d) => ({ ...d, [chunk.key]: chunk.default_text }))}
              >
                <RotateCcw className="size-4" /> 恢复默认
              </button>
            </div>
            {diff.removed.length > 0 ? (
              <details className="text-xs text-[var(--paf-muted)]">
                <summary>被删掉的默认规则（{diff.removed.length} 行）</summary>
                <ul className="mt-1 space-y-1 pl-4">
                  {diff.removed.map((line) => (
                    <li key={line} className="list-disc">
                      {line}
                    </li>
                  ))}
                </ul>
              </details>
            ) : null}
          </section>
        )
      })}

      <section className="paf-card space-y-2" data-panel="authors">
        <h2 className="paf-section-title">署名人</h2>
        {data.personas.length === 0 ? (
          <p className="text-sm text-amber-600 dark:text-amber-400" data-state="no-persona">
            一个可用角色都没有，正文不会署名，整批只会有一种中性口气。到「角色」页新建一个，或把已有角色的开关打开。
          </p>
        ) : (
          <>
            <p className="text-sm text-[var(--paf-muted)]" data-persona-count={data.personas.length}>
              共 {data.personas.length} 个可用角色，非「真实来源」的槽位会按兴趣域分给它们。
            </p>
            <ul className="space-y-1 text-sm">
              {data.personas.map((p) => (
                <li key={p.id} data-persona={p.id}>
                  <span className="font-medium">{p.name}</span>
                  {p.position ? (
                    <span className="text-[var(--paf-muted)]"> · {p.position}</span>
                  ) : null}
                </li>
              ))}
            </ul>
            <p className="text-xs text-[var(--paf-muted)]">
              想改某个角色的说法，去「角色」页改它自己的风格提示；这里只管怎么把它接进正文。
            </p>
          </>
        )}
      </section>

      <section className="paf-card space-y-2" data-panel="locked">
        <h2 className="paf-section-title">锁死的部分（只看，不能改）</h2>
        {data.locked.map((section) => (
          <details key={section.key} data-locked={section.key}>
            <summary className="text-sm">{section.title}</summary>
            <p className="mt-1 text-xs text-[var(--paf-muted)]">{section.why}</p>
            <pre className="mt-1 overflow-x-auto whitespace-pre-wrap text-xs text-[var(--paf-muted)]">
              {section.text}
            </pre>
          </details>
        ))}
      </section>

      <section className="paf-card space-y-2" data-panel="preview">
        <h2 className="paf-section-title">下一批实际提示词</h2>
        <p className="text-sm text-[var(--paf-muted)]">
          按当前已保存的设置拼一份 5 个槽位的完整提示词，只在界面里看，不发送、不计费。
        </p>
        <button
          type="button"
          className="paf-btn"
          onClick={() => void onPreview()}
          disabled={busy}
        >
          <Eye className="size-4" /> {preview ? '重新预览' : '预览实际提示词'}
        </button>
        {preview && stats ? (
          <div className="space-y-1" data-state="previewed">
            <p className="text-sm text-[var(--paf-muted)]">
              本次预览 {preview.slots} 条：真实来源 {preview.kind_counts?.sourced ?? 0} 条，
              AI 知识 {preview.kind_counts?.synthetic_explainer ?? 0} 条，
              虚构与连载 {(preview.kind_counts?.fiction ?? 0) + (preview.kind_counts?.serial ?? 0)} 条。
            </p>
            {preview.warnings?.map((warning) => (
              <p key={warning} className="text-sm text-amber-700 dark:text-amber-300">{warning}</p>
            ))}
            <pre
              className="max-h-[28rem] overflow-auto whitespace-pre-wrap rounded-lg bg-black/5 p-3 text-xs leading-relaxed dark:bg-white/10"
              data-preview-chars={stats.chars}
            >
              {preview.prompt}
            </pre>
            <div className="flex flex-wrap items-center justify-between gap-2 border-t border-[var(--paf-border)] pt-2">
              <p className="text-xs text-[var(--paf-muted)]">
                模型：{preview.provider} / {preview.model}
              </p>
              <p
                className="text-xs text-[var(--paf-muted)]"
                data-signed={stats.signedSlots}
                data-unsigned={stats.unsignedSlots}
                data-briefed={stats.briefedSlots}
                data-focused={stats.focusedSlots}
                data-formatted={stats.formattedSlots}
              >
                共 {stats.lines} 行 · {stats.chars} 字 · 已署名 {stats.signedSlots} 条 ·
                未署名 {stats.unsignedSlots} 条（真实来源本来就不该署名人）
              </p>
              <p className="text-xs text-[var(--paf-muted)]">
                选题细化：{stats.briefedSlots} 条带主题范围 · {stats.focusedSlots} 条带具体切口 ·{' '}
                {stats.formattedSlots} 条带本条写法（来自本机画像的 brief/seeds/formats）
              </p>
            </div>
            {stats.signedSlots === 0 ? (
              <p className="text-xs text-amber-600 dark:text-amber-400">
                这份提示词里一个署名都没有：正文会全部用同一种中性口气写。
              </p>
            ) : null}
            {stats.briefedSlots + stats.focusedSlots + stats.formattedSlots === 0 ? (
              <p className="text-xs text-amber-600 dark:text-amber-400">
                三个细化计数全为 0：要么本机画像没配 brief/seeds/formats，要么主题 id 没对上、或还没跑
                <code className="mx-1">just profile</code>把画像灌进库。配了却这里是 0，就是没落进提示词。
              </p>
            ) : null}
          </div>
        ) : null}
      </section>

      <div className="flex flex-wrap gap-2">
        <button type="button" className="paf-btn" onClick={() => void onSave()} disabled={busy}>
          <Save className="size-4" /> {busy ? '处理中…' : '保存提示词'}
        </button>
        <button
          type="button"
          className="paf-btn border border-[var(--paf-border)]"
          disabled={busy}
          onClick={() => setDrafts({
            style_rules: chunkByKey.get('style_rules')?.default_text ?? '',
            persona_notes: chunkByKey.get('persona_notes')?.default_text ?? '',
            angles: chunkByKey.get('angles')?.default_text ?? '',
          })}
        >
          <RotateCcw className="size-4" /> 三块全部填回默认
        </button>
      </div>
      <p className="text-xs text-[var(--paf-muted)]">
        {dirtyCount > 0
          ? `有 ${dirtyCount} 块改动还没保存；保存后才会进入下一批。`
          : '没有待保存的改动。'}
      </p>

      {notice ? (
        <NoticeBar tone={notice.tone} message={notice.message} onClose={() => setNotice(null)} />
      ) : null}
    </div>
  )
}
