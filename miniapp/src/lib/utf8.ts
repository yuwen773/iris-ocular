/**
 * 增量 UTF-8 解码器。
 *
 * <p>小程序的 {@code onChunkReceived} 给的是 ArrayBuffer 分片，分片边界可能落在
 * 一个汉字的三字节中间。逐片 new TextDecoder 会把这类汉字解成替换字符，
 * 流式回答里就会偶尔出现「?」。这里保留未完成的尾字节，等下一片拼上再解。
 */
export class Utf8StreamDecoder {
  private pending: Uint8Array = new Uint8Array(0)

  decode(chunk: Uint8Array): string {
    if (chunk.length === 0) return ''
    const bytes = new Uint8Array(this.pending.length + chunk.length)
    bytes.set(this.pending, 0)
    bytes.set(chunk, this.pending.length)

    const end = completePrefixLength(bytes)
    this.pending = bytes.subarray(end)
    return decodeComplete(bytes.subarray(0, end))
  }

  /** 流结束时调用：残留的（不完整）字节按 UTF-8 语义丢弃，避免产出乱码。 */
  flush(): string {
    this.pending = new Uint8Array(0)
    return ''
  }
}

function sequenceLength(lead: number): number {
  if (lead < 0x80) return 1
  if ((lead & 0xe0) === 0xc0) return 2
  if ((lead & 0xf0) === 0xe0) return 3
  if ((lead & 0xf8) === 0xf0) return 4
  return 1
}

/** 从头扫描，返回最后一个完整字符的结束位置。 */
function completePrefixLength(bytes: Uint8Array): number {
  let index = 0
  while (index < bytes.length) {
    const length = sequenceLength(bytes[index])
    if (index + length > bytes.length) {
      return index
    }
    index += length
  }
  return index
}

function decodeComplete(bytes: Uint8Array): string {
  let text = ''
  let index = 0
  while (index < bytes.length) {
    const first = bytes[index]
    let codePoint: number
    let length: number
    if (first < 0x80) {
      codePoint = first
      length = 1
    } else if ((first & 0xe0) === 0xc0) {
      codePoint = ((first & 0x1f) << 6) | (bytes[index + 1] & 0x3f)
      length = 2
    } else if ((first & 0xf0) === 0xe0) {
      codePoint = ((first & 0x0f) << 12) | ((bytes[index + 1] & 0x3f) << 6) | (bytes[index + 2] & 0x3f)
      length = 3
    } else {
      codePoint =
        ((first & 0x07) << 18) |
        ((bytes[index + 1] & 0x3f) << 12) |
        ((bytes[index + 2] & 0x3f) << 6) |
        (bytes[index + 3] & 0x3f)
      length = 4
    }
    index += length
    if (codePoint > 0xffff) {
      const offset = codePoint - 0x10000
      text += String.fromCharCode(0xd800 + (offset >> 10), 0xdc00 + (offset & 0x3ff))
    } else {
      text += String.fromCharCode(codePoint)
    }
  }
  return text
}
