# VoxFix 手机助手入口 —— 双击此文件（.pyw = 无控制台启动）
# 职责：USB 插线自动投屏 / 拔线自动关闭 / 保活剪贴板同步（详见 voxfix_phone_companion.py）
import os
import runpy

os.chdir(r"D:\Projects\VoxFix")
runpy.run_path(r"D:\Projects\VoxFix\tools\voxfix_phone_companion.py", run_name="__main__")
