// 画布显示规则（与安卓侧 ScreenshotCanvasFit.kt 同参同结果）。
// 截图时预览与编辑时必须用同一套规则，否则用户看到的和编辑到的不是同一个画面。
export function imageFit(imageWidth, imageHeight, viewWidth, viewHeight) {
  if (!(imageWidth > 0 && imageHeight > 0 && viewWidth > 0 && viewHeight > 0)) {
    return {containScale: 1, widthScale: 1, canFitWidth: false};
  }
  const containScale = Math.min(1, viewWidth / imageWidth, viewHeight / imageHeight);
  const widthScale = Math.min(1, viewWidth / imageWidth);
  return {containScale, widthScale, canFitWidth: widthScale > containScale * 1.05};
}
