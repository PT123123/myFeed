import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'

import './index.css'
import App from './App.tsx'
import { bootstrapMobile } from './mobile/bootstrap'
import { installNativeFetch, isMobileHost } from './mobile/transport'
import { readStoredTheme, applyThemeClass } from './lib/theme'

applyThemeClass(readStoredTheme())

installNativeFetch()
const render = () => createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
if (isMobileHost()) {
  void bootstrapMobile().then(render).catch((error: unknown) => {
    document.getElementById('root')!.textContent = `LLM Feed 初始化失败：${String(error)}`
  })
} else render()
