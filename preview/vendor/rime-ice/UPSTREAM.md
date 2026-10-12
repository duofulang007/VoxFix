# Offline English dictionary

Source: https://github.com/iDvel/rime-ice
Commit: da1fbe602e38f26db846fa10120ee64c2b0324c0
File: en_dicts/en.dict.yaml
Source SHA-256: d08db21af6c686bfafa4d62926e33ea685319927a374acd99dde84a351b5aa35
License: upstream GPL-3.0, preserved in LICENSE. Upstream source attributions are retained below.

# Rime dictionary
# encoding: utf-8
#
#
# https://github.com/iDvel/rime-ice
# ------- 英文主词库 -------
# 20k 左右的常见单词 + 少许补充
#
# en.dict.yaml 放置正经单词
# en_ext.dict.yaml 大部分是缩写或互联网相关
#
# 来源：
# 常见两万词 [20k.txt](https://github.com/first20hours/google-10000-english/)
# 合并不重复的 1000 多左右 [融合拼音方案](https://github.com/tumuyan/rime-melt) 的英文词库
#
# 注释掉单个字母、绝大部分两个字母的缩写、冷门缩写
# 转化应当大写的单词

VoxFix transformation: exclude comments and non-word rows, normalize case, deduplicate and sort. No runtime network request.
