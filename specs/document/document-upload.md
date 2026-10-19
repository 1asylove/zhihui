# 文档上传与解析规格（Document Upload & Parse Spec）

## 1. 目标
解决文件上传与解析问题

## 2. 范围
### 做
- 支持 txt / pdf / docx 三种格式
- 落盘 D:/ai/uploads
### 不做
- 切片、向量化、入库（留给后续模块）

## 3. 功能需求
- FR-1：`POST /documents/upload` ，参数`file` ，成功后返回上传文件名 + 解析出的正文
- FR-2：当`file` 为空时，接口直接返回`文件为空` ，不再落盘和解析
- FR-3：系统按文件扩展名（统一转小写）分发到对应解析器；命不中返回`暂不支持的文件类型：{ext}`

## 4. 接口契约
- 见 contracts/document.openapi.yaml（先占位，下一步再写)

## 5. 验收标准
- [ ] 上传 txt → 返回解析出的正文
- [ ] 上传 pdf → 返回 PDF 文本
- [ ] 上传 docx → 返回 Word 文本