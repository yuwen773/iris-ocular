import { ensureLogin, relogin } from './auth'
import { API_BASE } from './env'
import { ApiError, ERROR_CODE, errorForStatus, unpack } from './raw'
import { getToken } from './token'
import type { DiagnosisResult } from './api'

/** 服务端 `/api/diagnosis/diagnosis` 的真实上限（FileManager.validPicture）。 */
export const MAX_IMAGE_BYTES = 2 * 1024 * 1024

/** 离屏画布缩放的等比上限（最长边）：只用来把极端大图拉回 2MB 以内。 */
const RESIZE_MAX_EDGE = 1600

export class ImageTooLargeError extends Error {
  constructor(public readonly bytes: number) {
    super(`影像压缩后仍有 ${(bytes / 1024 / 1024).toFixed(1)}MB，超过 2MB 上限`)
    this.name = 'ImageTooLargeError'
  }
}

function compress(src: string, quality: number): Promise<string> {
  return new Promise((resolve, reject) => {
    wx.compressImage({
      src,
      quality,
      success: (res) => resolve(res.tempFilePath),
      fail: () => reject(new Error('影像压缩失败，请重试')),
    })
  })
}

function fileSize(filePath: string): Promise<number> {
  return new Promise((resolve, reject) => {
    wx.getFileSystemManager().getFileInfo({
      filePath,
      success: (res) => resolve(res.size),
      fail: () => reject(new Error('读取影像信息失败，请重试')),
    })
  })
}

interface OffscreenImage {
  src: string
  width: number
  height: number
  onload?: () => void
  onerror?: () => void
}

interface OffscreenCanvas2D {
  width: number
  height: number
  getContext: (contextType: '2d') => { drawImage: (image: OffscreenImage, x: number, y: number, w: number, h: number) => void } | null
  createImage: () => OffscreenImage
}

interface ImageSize {
  width: number
  height: number
}

function imageSize(src: string): Promise<ImageSize> {
  return new Promise((resolve, reject) => {
    wx.getImageInfo({
      src,
      success: (res) => resolve({ width: res.width, height: res.height }),
      fail: () => reject(new Error('读取影像尺寸失败')),
    })
  })
}

/**
 * 离屏画布等比缩放后按 JPEG 0.8 重新编码，返回新文件路径。
 *
 * <p>只在 `compressImage` 两档都不够时兜底。任何一步不可用（旧基础库没有
 * `createOffscreenCanvas`、`getContext('2d')` 返回空、导出失败）都返回 null，
 * 由调用方按「超限请重拍」处理——不把没验证过的路径当成成功。
 */
async function shrinkOnCanvas(src: string): Promise<string | null> {
  const api = wx as unknown as { createOffscreenCanvas?: (options: { type: '2d' }) => OffscreenCanvas2D }
  if (typeof api.createOffscreenCanvas !== 'function') return null
  try {
    const size = await imageSize(src)
    const scale = Math.min(1, RESIZE_MAX_EDGE / Math.max(size.width, size.height))
    const targetWidth = Math.max(1, Math.round(size.width * scale))
    const targetHeight = Math.max(1, Math.round(size.height * scale))

    const canvas = api.createOffscreenCanvas({ type: '2d' })
    canvas.width = targetWidth
    canvas.height = targetHeight
    const context = canvas.getContext('2d')
    if (!context) return null

    const image = canvas.createImage()
    await new Promise<void>((resolve, reject) => {
      image.onload = () => resolve()
      image.onerror = () => reject(new Error('绘制影像失败'))
      image.src = src
    })
    context.drawImage(image, 0, 0, targetWidth, targetHeight)

    const exported = await new Promise<{ tempFilePath: string }>((resolve, reject) => {
      const toTempFilePath = wx.canvasToTempFilePath as unknown as (options: {
        canvas: OffscreenCanvas2D
        x: number
        y: number
        width: number
        height: number
        destWidth: number
        destHeight: number
        fileType: 'jpg'
        quality: number
        success: (res: { tempFilePath: string }) => void
        fail: (error: unknown) => void
      }) => void
      toTempFilePath({
        canvas,
        x: 0,
        y: 0,
        width: targetWidth,
        height: targetHeight,
        destWidth: targetWidth,
        destHeight: targetHeight,
        fileType: 'jpg',
        quality: 0.8,
        success: resolve,
        fail: () => reject(new Error('导出影像失败')),
      })
    })
    return exported.tempFilePath
  } catch {
    return null
  }
}

/**
 * 选图 + 压到服务端上限内。
 *
 * <p>顺序：`chooseMedia(compressed)` → `compressImage` 70 档 → 40 档 →
 * 离屏画布等比缩到最长边 1600 并重编码。四步都过不了 2MB 才报错让用户重拍，
 * 而不是偷偷传一张服务端必然拒收的图。
 */
export async function pickAndPrepareImage(): Promise<string> {
  const picked = await chooseImage()
  const first = await compress(picked, 70)
  if ((await fileSize(first)) <= MAX_IMAGE_BYTES) return first

  const second = await compress(picked, 40)
  if ((await fileSize(second)) <= MAX_IMAGE_BYTES) return second

  const shrunk = await shrinkOnCanvas(first)
  if (shrunk && (await fileSize(shrunk)) <= MAX_IMAGE_BYTES) return shrunk

  throw new ImageTooLargeError(shrunk ? await fileSize(shrunk) : await fileSize(second))
}

function chooseImage(): Promise<string> {
  return new Promise((resolve, reject) => {
    wx.chooseMedia({
      count: 1,
      mediaType: ['image'],
      sizeType: ['compressed'],
      sourceType: ['camera', 'album'],
      success: (res) => {
        const file = res.tempFiles[0]
        if (!file) {
          reject(new Error('未选择影像'))
          return
        }
        resolve(file.tempFilePath)
      },
      fail: (error) => {
        // 用户取消不算错误：给一个可识别的标记，页面据此不显示失败提示。
        reject(new Error(error.errMsg?.includes('cancel') ? 'cancelled' : '选择影像失败'))
      },
    })
  })
}

/**
 * 上传并同步判读。`runId` 是客户端生成、服务端登记的运行标识，
 * 只有它能让 `/diagnosis/cancel/{runId}` 真正停下这次判读。
 *
 * <p>登录态与 `request.ts` 同口径：先确保有令牌，服务端回 40100 就重登一次再试。
 * 上传走 `wx.uploadFile`（multipart），不经过 Taro 的 request 封装，因此这层
 * 需要自己补上重登——否则首启抢拍会带着空令牌直接撞 403。
 */
export async function uploadDiagnosis(filePath: string, runId: string): Promise<DiagnosisResult> {
  await ensureLogin()
  try {
    return await uploadOnce(filePath, runId)
  } catch (error) {
    if (error instanceof ApiError && error.code === ERROR_CODE.NOT_LOGIN) {
      await relogin()
      return uploadOnce(filePath, runId)
    }
    throw error
  }
}

function uploadOnce(filePath: string, runId: string): Promise<DiagnosisResult> {
  return new Promise((resolve, reject) => {
    wx.uploadFile({
      url: `${API_BASE}/diagnosis/diagnosis?runId=${encodeURIComponent(runId)}`,
      filePath,
      name: 'file',
      header: { Authorization: `Bearer ${getToken() ?? ''}` },
      timeout: 60000,
      success: (res) => {
        const statusError = errorForStatus(res.statusCode)
        if (statusError) {
          reject(statusError)
          return
        }
        let body: unknown
        try {
          body = JSON.parse(res.data)
        } catch {
          reject(new ApiError(ERROR_CODE.MALFORMED, '服务端返回格式异常'))
          return
        }
        try {
          resolve(unpack<DiagnosisResult>(body))
        } catch (error) {
          reject(error)
        }
      },
      fail: () => reject(new ApiError(ERROR_CODE.NETWORK, '网络连接失败，请检查网络后重试')),
    })
  })
}
