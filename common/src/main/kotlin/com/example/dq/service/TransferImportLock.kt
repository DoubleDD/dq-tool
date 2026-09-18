package com.example.dq.service

/**
 * 标注/扫描记录/元数据导入的全局互斥锁。
 *
 * 扫描记录导入的任务判重(同数据源+db+schema+创建时间)与标记按名合并都是「先查后写」:
 * 两个导入并发执行时(两个浏览器页签同时拉取、拉取与手工导入并行)会双双通过判重、
 * 产生重复扫描任务,并在 tag_def.name 唯一键上撞「主键冲突」导致导入失败。
 * 导入是低频批量写,而 H2 为进程内嵌库(实例锁保证单进程),用进程内锁把
 * ScanTransferService / AnnotationTransferService / MetadataTransferService
 * 三个导入入口互相串行即可根除导入间的竞态;
 * 导入与手工新建标记之间的竞态由 TagRepository.createIfAbsent(撞唯一键读回)兜底。
 */
internal object TransferImportLock
