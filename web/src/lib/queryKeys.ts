/**
 * 查询键的唯一来源。
 *
 * <p>键里带什么，决定「什么时候重新取数」：统计按窗口天数分键，切换窗口就是
 * 另一个键，旧数据立刻不可见。失效也按前缀来——写操作只失效它真正影响的域。
 *
 * <p>个人域（records / profile）的键里必须带当前账号的 <b>scope</b>：同一台
 * 浏览器换账号登录后，B 读的是 B 的键，A 的旧缓存即使还活着也不会被读到。
 * 账号变化时再整域清一次（见 queryClient.resetPrivateQueries），两层保险。
 * 管理域是服务端全局数据，不按账号分键，但同样在身份变化时清空。
 */
import type {
  AdminFeedbackQuery,
  DiagnosisQuery,
  NewsQuery,
  UserQuery,
  KnowledgeQuery,
} from "./adminApi"
import type { NotificationQuery } from "./api"

/**
 * 跟登录身份走、不能跨账号复用的缓存域。身份一变就整域作废。
 *
 * <p>`feedback`（用户侧「我的反馈」）与 `notifications`（站内通知）是个人域，必须在这里；
 * 管理端的反馈台账已经在 `admin` 域下，不需要重复登记。
 */
export const PRIVATE_QUERY_ROOTS = [
  "records",
  "profile",
  "admin",
  "feedback",
  "notifications",
] as const

export const queryKeys = {
  admin: {
    stats: (days: number) => ["admin", "stats", days] as const,
    statsAll: ["admin", "stats"] as const,
    /** 数据分析读数：与总览分键，避免切页时互相串用缓存。 */
    analytics: (days: number) => ["admin", "analytics", days] as const,
    analyticsAll: ["admin", "analytics"] as const,
    recentDiagnoses: ["admin", "diagnosis", "recent"] as const,
    users: (q: UserQuery) => ["admin", "users", q] as const,
    usersAll: ["admin", "users"] as const,
    news: (q: NewsQuery) => ["admin", "news", q] as const,
    newsAll: ["admin", "news"] as const,
    newsDetail: (id: string) => ["admin", "news", "detail", id] as const,
    newsRevisions: (id: string) => ["admin", "news", "revisions", id] as const,
    newsWorkbench: {
      all: ["admin", "newsWorkbench"] as const,
      overview: ["admin", "newsWorkbench", "overview"] as const,
      sources: ["admin", "newsWorkbench", "sources"] as const,
      schedule: ["admin", "newsWorkbench", "schedule"] as const,
      tasks: (q: { source?: string; status?: string; current: number; pageSize: number }) =>
        ["admin", "newsWorkbench", "tasks", q] as const,
      taskLeases: (taskId: string) => ["admin", "newsWorkbench", "taskLeases", taskId] as const,
      taskEvents: (taskId: string) => ["admin", "newsWorkbench", "taskEvents", taskId] as const,
      candidates: (q: {
        status?: string
        source?: string
        evidenceTier?: string
        current: number
        pageSize: number
      }) => ["admin", "newsWorkbench", "candidates", q] as const,
      candidateDetail: (id: string) => ["admin", "newsWorkbench", "candidateDetail", id] as const,
      budgets: (scope?: string) => ["admin", "newsWorkbench", "budgets", scope ?? ""] as const,
      receipts: (q: { state?: string; budgetScope?: string; current: number; pageSize: number }) =>
        ["admin", "newsWorkbench", "receipts", q] as const,
      benchRuns: ["admin", "newsWorkbench", "benchRuns"] as const,
      curationMaterials: (q: { source?: string; current: number; pageSize: number }) =>
        ["admin", "newsWorkbench", "curationMaterials", q] as const,
      materialEvidence: (materialRevisionId: string) =>
        ["admin", "newsWorkbench", "materialEvidence", materialRevisionId] as const,
      materialReceipts: (materialRevisionId: string) =>
        ["admin", "newsWorkbench", "materialReceipts", materialRevisionId] as const,
      issuePreview: (startOn: string, endOn: string) =>
        ["admin", "newsWorkbench", "issuePreview", startOn, endOn] as const,
    },
    diagnosis: (q: DiagnosisQuery) => ["admin", "diagnosis", q] as const,
    diagnosisAll: ["admin", "diagnosis"] as const,
    diagnosisDetail: (id: string) => ["admin", "diagnosis", "detail", id] as const,
    knowledge: (q: KnowledgeQuery) => ["admin", "knowledge", q] as const,
    knowledgeAll: ["admin", "knowledge"] as const,
    knowledgeDetail: (id: string) => ["admin", "knowledge", "detail", id] as const,
    feedback: (q: AdminFeedbackQuery) => ["admin", "feedback", q] as const,
    feedbackAll: ["admin", "feedback"] as const,
    feedbackPendingCount: ["admin", "feedback", "pending-count"] as const,
  },
  records: {
    mine: (
      scope: string,
      q: { current?: number; pageSize?: number; result?: string },
    ) => ["records", "mine", scope, q] as const,
    /**
     * 账号级统计：与分页无关，所以键里不带页码。
     * 翻页时列表换键、统计不换——否则同一个全量接口会被翻页重复请求，
     * 个人中心与记录页也拿不到同一份缓存。
     */
    stats: (scope: string) => ["records", "stats", scope] as const,
    detail: (scope: string, id: string) => ["records", "detail", scope, id] as const,
  },
  profile: {
    user: (scope: string) => ["profile", "user", scope] as const,
  },
  /**
   * 我的反馈。键里带 scope：换账号后 B 读的是 B 的键，
   * 上一账号提交的反馈不会在列表里闪一下。
   */
  feedback: {
    mine: (scope: string, q: { current?: number; pageSize?: number }) =>
      ["feedback", "mine", scope, q] as const,
    /**
     * 关联对象下拉的取样：与判读记录页的键分开，不互相顶掉缓存。
     * 判读任务取的是 `/diagnosis-tasks`（taskId 是提交时真正要送的那个 id）。
     */
    targetTasks: (scope: string) => ["feedback", "target-tasks", scope] as const,
    targetSessions: (scope: string) => ["feedback", "target-sessions", scope] as const,
  },
  /**
   * 站内通知。列表与未读数分开分键：
   * 打开通知页会把未读清成已读，但列表本身的分页不应因此重新取数。
   */
  notifications: {
    list: (scope: string, q: NotificationQuery) => ["notifications", "list", scope, q] as const,
    listAll: (scope: string) => ["notifications", "list", scope] as const,
    unread: (scope: string) => ["notifications", "unread", scope] as const,
  },
  news: {
    list: () => ["news", "list"] as const,
    detail: (id: string) => ["news", "detail", id] as const,
  },
} as const
