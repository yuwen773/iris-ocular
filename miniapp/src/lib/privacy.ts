/**
 * 隐私授权。
 *
 * <p>自 2023-10-17 起，小程序在调用相机/相册等接口前必须先完成隐私授权。
 * 未在小程序后台声明对应信息时，平台会直接禁用接口；这里的调用只是把平台的
 * 授权弹窗在「用户第一次要拍照」这个时刻触发，而不是一进小程序就弹。
 *
 * <p>平台弹窗只有「同意/拒绝」，不说明为什么要你的影像——所以页面先用自建弹层
 * （`components/PrivacyDialog`）讲清用途，用户点「同意并继续」后再调
 * {@link requirePrivacyAuthorize} 走平台确认。
 *
 * <p>基础库低于 2.32.3 没有这组接口——此时视为无需授权，不阻塞功能；
 * 真机上是否合规以公众平台的《用户隐私保护指引》声明为准。
 */
export interface PrivacyStatus {
  /** true 表示还没授权，调用相机/相册前必须先走一次授权。 */
  needAuthorization: boolean
  /** 平台后台配置的隐私指引名称，用于弹层文案。 */
  contractName?: string
}

interface PrivacyApi {
  getPrivacySetting?: (options: {
    success: (res: { needAuthorization: boolean; privacyContractName?: string }) => void
    fail: () => void
  }) => void
  requirePrivacyAuthorize?: (options: { success: () => void; fail: () => void }) => void
  openPrivacyContract?: (options: { fail?: () => void }) => void
}

function privacyApi(): PrivacyApi {
  return wx as unknown as PrivacyApi
}

export function getPrivacyStatus(): Promise<PrivacyStatus> {
  return new Promise((resolve) => {
    const api = privacyApi()
    if (!api.getPrivacySetting) {
      resolve({ needAuthorization: false })
      return
    }
    api.getPrivacySetting({
      success: (res) =>
        resolve({
          needAuthorization: Boolean(res.needAuthorization),
          contractName: res.privacyContractName,
        }),
      // 查不到状态不挡功能：真机上平台仍会在调用相机时自行拦截。
      fail: () => resolve({ needAuthorization: false }),
    })
  })
}

/** 弹平台的隐私授权框；返回是否已授权。旧基础库视为已授权。 */
export function requirePrivacyAuthorize(): Promise<boolean> {
  return new Promise((resolve) => {
    const api = privacyApi()
    if (!api.requirePrivacyAuthorize) {
      resolve(true)
      return
    }
    api.requirePrivacyAuthorize({
      success: () => resolve(true),
      fail: () => resolve(false),
    })
  })
}

/** 一步到位：需要授权就先弹平台授权框。 */
export async function ensurePrivacyAuthorized(): Promise<boolean> {
  const status = await getPrivacyStatus()
  if (!status.needAuthorization) return true
  return requirePrivacyAuthorize()
}

/** 打开《小程序用户隐私保护指引》全文。 */
export function openPrivacyContract(): void {
  privacyApi().openPrivacyContract?.({ fail: () => undefined })
}
