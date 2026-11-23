import { Button, Text, View } from '@tarojs/components'
import Taro, { useDidShow } from '@tarojs/taro'
import { useState } from 'react'
import { Disclaimer } from '@/components/Feedback'
import { fetchCurrentUser, isAutoLoginSuspended, logout, resumeLogin, type LoginUser } from '@/lib/auth'
import { openPrivacyContract } from '@/lib/privacy'
import { ApiError } from '@/lib/raw'
import './index.scss'

const ROLE_LABEL: Record<string, string> = {
  user: '注册用户',
  doctor: '医生',
  admin: '管理员',
}

export default function ProfilePage() {
  const [user, setUser] = useState<LoginUser | null>(null)
  const [suspended, setSuspended] = useState(false)
  const [fault, setFault] = useState('')

  async function load() {
    setSuspended(isAutoLoginSuspended())
    if (isAutoLoginSuspended()) {
      setUser(null)
      return
    }
    try {
      setUser(await fetchCurrentUser())
      setFault('')
    } catch (error) {
      setFault(error instanceof ApiError ? error.message : '读取账号信息失败')
    }
  }

  useDidShow(() => {
    void load()
  })

  async function handleLogout() {
    await logout()
    setSuspended(true)
    setUser(null)
    void Taro.showToast({ title: '已退出登录', icon: 'none' })
  }

  async function handleResume() {
    try {
      await resumeLogin()
      setSuspended(false)
      await load()
    } catch (error) {
      setFault(error instanceof ApiError ? error.message : '登录失败，请重试')
    }
  }

  return (
    <View className="mp-page">
      <View className="mp-card">
        {suspended ? (
          <>
            <Text className="mp-title">已退出登录</Text>
            <Text className="mp-subtitle">重新登录会回到同一个微信账号，历史记录不会丢失。</Text>
            <Button className="mp-btn" style={{ marginTop: '24rpx' }} onClick={handleResume}>
              重新登录
            </Button>
          </>
        ) : user ? (
          <>
            <Text className="mp-label">当前账号</Text>
            <View className="profile-name">{user.userName || '微信用户'}</View>
            <View className="mp-stack" style={{ marginTop: '12rpx' }}>
              <Text className="mp-note">账号 {user.userAccount}</Text>
              <Text className="mp-note">角色 {ROLE_LABEL[user.userRole] ?? user.userRole}</Text>
            </View>
          </>
        ) : (
          <Text className="mp-note">{fault || '正在读取账号信息…'}</Text>
        )}
      </View>

      <View className="mp-card">
        <View className="mp-stack">
          <Button className="mp-btn mp-btn--ghost" onClick={openPrivacyContract}>
            查看《小程序用户隐私保护指引》
          </Button>
          {!suspended ? (
            <Button className="mp-btn mp-btn--danger" onClick={handleLogout}>
              退出登录
            </Button>
          ) : null}
        </View>
        <Text className="mp-note" style={{ marginTop: '24rpx' }}>
          退出只影响本次小程序运行；再次打开会重新以当前微信身份登录。
        </Text>
      </View>

      <View className="mp-card">
        <Text className="mp-label">关于</Text>
        <View className="mp-stack" style={{ marginTop: '12rpx' }}>
          <Text className="mp-note">Iris 眼底影像判读 · 患者端 0.1.0（体验版）</Text>
          <Text className="mp-note">
            判读与咨询内容由 AI 生成，用于健康参考，不构成医学诊断，也不能替代眼科医生的面诊。
          </Text>
        </View>
      </View>

      <Disclaimer />
    </View>
  )
}
