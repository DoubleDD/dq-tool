<template>
  <div class="page-card" :class="{ 'er-fullheight': activeTab === 'er' || activeTab === 'graph' }">
    <div class="toolbar">
      <Breadcrumb :items="breadcrumbItems" />
      <div>
        <el-button type="primary" :loading="collectLoading" :disabled="!dsId" @click="toggleCollect">
          {{ collectId ? '取消采集' : '采集' }}
        </el-button>
        <ExportButton v-if="hasJob" :job-id="jobId" label="导出扫描结果" />
        <el-button :loading="erExporting" @click="exportExcel">{{ activeTab === 'er' ? '导出ER表格' : '导出列表' }}</el-button>
        <el-button :icon="Refresh" :loading="refreshing" @click="refreshAll">刷新</el-button>
      </div>
    </div>

    <!-- 扫描信息:有扫描时展示统计概览;无扫描时提示仅结构 -->
    <el-alert v-if="hasJob && jobTable" type="success" :closable="false" style="margin-bottom: 16px">
      <span style="margin-right: 24px">总行数: {{ formatNumber(jobTable.totalRows ?? jobTable.scannedRows) }}</span>
      <span style="margin-right: 24px">
        统计方式: {{ jobTable.sampled ? '采样' : '全量' }}
        <el-tooltip v-if="jobTable.sampled" placement="top" :show-after="200">
          <template #content>
            <div>该表只统计了样本(默认 10 万行),空值数、有值率等由样本按比例推算,为估算值</div>
            <div>MySQL / 达梦 / OceanBase 为 LIMIT 顺序采样,结果可能有偏</div>
          </template>
          <el-tag type="warning" size="small" style="margin-left: 6px">估算值</el-tag>
        </el-tooltip>
      </span>
      <span v-if="jobTable.sampled" style="margin-right: 24px">采样行数: {{ formatNumber(jobTable.sampleRows) }}</span>
      <span style="margin-right: 24px">耗时: {{ formatDuration(jobTable.startedAt, jobTable.finishedAt) }}</span>
      <span style="margin-right: 24px">字段数量: {{ columns.length }}</span>
      <span>
        空字段数量:
        <el-link type="primary" :disabled="!emptyColumns.length" @click="onlyEmpty = true">{{ emptyColumns.length }}</el-link>
      </span>
    </el-alert>
    <el-alert v-else type="info" :closable="false" style="margin-bottom: 12px">
      该表尚未扫描,以下为数据库元数据中的字段结构与索引结构,不含空值与有值率统计。
      在表列表勾选该表后「开始扫描」,扫描完成即可查看字段级统计。
    </el-alert>

    <el-tabs v-model="activeTab" @tab-change="onTabChange">
      <el-tab-pane label="字段明细" name="columns">
        <div style="display: flex; gap: 16px; align-items: center; margin-bottom: 12px">
          <template v-if="columnView === 'table'">
            <el-input v-model="keyword" placeholder="按字段名或注释搜索" clearable style="width: 280px" />
            <el-checkbox v-if="hasJob" v-model="onlyEmpty">只看空字段(有值数为 0)</el-checkbox>
          </template>
          <el-button v-else size="small" type="primary" plain :disabled="!ddlText" @click="copyDdl">复制 DDL</el-button>
          <!-- 表格 / DDL 视图切换(靠右) -->
          <el-radio-group v-model="columnView" size="small" style="margin-left: auto" @change="onColumnViewChange">
            <el-radio-button value="table">表格</el-radio-button>
            <el-radio-button value="ddl">DDL</el-radio-button>
          </el-radio-group>
        </div>

        <!-- DDL 视图:建表语句(含索引),懒加载;本地缓存优先,数据源不可达时降级展示缓存 -->
        <template v-if="columnView === 'ddl'">
          <el-alert v-if="ddlError" type="error" :closable="false" show-icon :title="ddlError" style="margin-bottom: 12px" />
          <el-alert v-else-if="ddlFromCache" type="warning" :closable="false" show-icon style="margin-bottom: 12px">
            <span>数据源当前不可达,展示的是<b>本地缓存</b>的 DDL;恢复网络后点「刷新」可重新同步。</span>
          </el-alert>
          <div v-loading="ddlLoading">
            <pre v-if="ddlText" class="ddl-view">{{ ddlText }}</pre>
            <el-empty v-if="!ddlLoading && !ddlError && ddlLoaded && !ddlText" description="未获取到 DDL" :image-size="60" />
          </div>
        </template>

        <!-- 字段列表:基础结构列 + (已扫描时)统计列;表头吸顶走全局 sticky 口径(style.css),页签内需放开 tabs content 裁剪见下方样式 -->
        <el-table v-else :data="filteredColumns" v-loading="loading" border>
          <el-table-column type="index" label="序号" width="60" />
          <el-table-column prop="name" label="字段名" min-width="140" sortable show-overflow-tooltip />
          <el-table-column prop="comment" label="注释" min-width="140" sortable show-overflow-tooltip>
            <template #default="{ row }">
              <span v-if="row.comment">{{ row.comment }}</span>
              <span v-else style="color: var(--el-text-color-placeholder)">-</span>
            </template>
          </el-table-column>
          <el-table-column prop="displayType" label="类型" width="150" sortable show-overflow-tooltip />
          <el-table-column label="键" width="70" sortable :sort-method="(a, b) => keyLabel(a).localeCompare(keyLabel(b))">
            <template #default="{ row }">
              <el-tag v-if="keyLabel(row)" size="small" :type="keyLabel(row) === 'PK' ? 'primary' : 'success'">{{ keyLabel(row) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="可空" width="80" sortable :sort-method="(a, b) => Number(a.nullable ?? true) - Number(b.nullable ?? true)">
            <template #default="{ row }">
              <span v-if="row.nullable === null || row.nullable === undefined">-</span>
              <span v-else>{{ row.nullable ? '是' : '否' }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="defaultValue" label="默认值" width="110" show-overflow-tooltip>
            <template #default="{ row }">
              <span v-if="row.defaultValue !== null && row.defaultValue !== undefined && row.defaultValue !== ''">{{ row.defaultValue }}</span>
              <span v-else style="color: var(--el-text-color-placeholder)">-</span>
            </template>
          </el-table-column>
          <template v-if="hasJob">
            <el-table-column label="空值数(合计)" width="150" sortable :sort-method="sortByNullTotal">
              <template #default="{ row }">
                <span :style="{ color: nullTotal(row) > 0 ? 'var(--el-color-warning)' : 'inherit' }">{{ formatNumber(nullTotal(row)) }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="totalRows" label="总行数" width="110" sortable>
              <template #default="{ row }">{{ formatNumber(row.totalRows) }}</template>
            </el-table-column>
            <el-table-column prop="nullCount" label="NULL 数" width="110" sortable>
              <template #default="{ row }">{{ formatNumber(row.nullCount) }}</template>
            </el-table-column>
            <el-table-column prop="emptyCount" label="空串数" width="110" sortable>
              <template #default="{ row }">{{ formatNumber(row.emptyCount) }}</template>
            </el-table-column>
            <el-table-column prop="valueCount" label="有值数" width="110" sortable>
              <template #default="{ row }">{{ formatNumber(row.valueCount) }}</template>
            </el-table-column>
            <el-table-column label="有值率" width="180" sortable :sort-method="(a, b) => (a.fillRate || 0) - (b.fillRate || 0)">
              <template #default="{ row }">
                <el-progress
                  :percentage="row.fillRate || 0"
                  :stroke-width="10"
                  :color="row.fillRate >= 95 ? 'var(--el-color-success)' : row.fillRate >= 80 ? 'var(--el-color-warning)' : 'var(--el-color-danger)'"
                />
              </template>
            </el-table-column>
          </template>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="索引结构" name="indexes">
        <el-table :data="indexes" v-loading="indexesLoading" border size="small">
          <el-table-column type="index" label="序号" width="60" />
          <el-table-column prop="name" label="索引名" min-width="180" show-overflow-tooltip />
          <el-table-column label="唯一" width="90">
            <template #default="{ row }">
              <el-tag v-if="row.unique" type="success" size="small">唯一</el-tag>
              <span v-else style="color: var(--el-text-color-placeholder)">-</span>
            </template>
          </el-table-column>
          <el-table-column label="索引列" min-width="200">
            <template #default="{ row }">
              <el-tag v-for="c in row.columns" :key="c" size="small" style="margin: 0 4px 2px 0">{{ c }}</el-tag>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="数据预览" name="preview">
        <div style="display: flex; gap: 8px; margin-bottom: 12px">
          <SqlInput v-model="previewWhere" label="WHERE" :columns="completionColumns"
                    placeholder="过滤条件,如 status = 'A' AND age > 18(回车应用)"
                    @enter="applyPreviewFilter" />
          <SqlInput v-model="previewOrderBy" label="ORDER BY" :columns="completionColumns"
                    placeholder="排序,如 id desc(回车应用)"
                    @enter="applyPreviewFilter" />
          <el-button size="small" type="primary" plain @click="applyPreviewFilter">应用</el-button>
        </div>
        <el-alert v-if="previewError" type="error" :closable="false" show-icon :title="previewError" style="margin-bottom: 12px" />
        <el-table v-else :data="previewTableData" v-loading="previewLoading" border size="small" class="preview-table"
                  @cell-click="copyPreviewCell">
          <el-table-column type="index" label="#" width="50" :index="(previewPage - 1) * previewSize + 1" />
          <el-table-column v-for="col in previewColumns" :key="col.key" :prop="col.key" min-width="140" show-overflow-tooltip>
            <template #header>
              <div class="pv-h-name">{{ col.name }}</div>
              <div class="pv-h-sub">{{ col.type }}</div>
              <div class="pv-h-sub pv-h-comment" :title="col.comment || undefined">
                {{ col.comment || '—' }}
              </div>
            </template>
            <template #default="{ row }">
              <span v-if="row[col.key] !== null && row[col.key] !== undefined">{{ row[col.key] }}</span>
              <span v-else style="color: var(--el-text-color-placeholder)">NULL</span>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!previewLoading && !previewError && previewLoaded && !previewRows.length"
                  description="表无数据" :image-size="60" />
        <div v-if="previewLoaded && previewTotal > 0" class="pagination-wrapper">
          <el-pagination v-model:current-page="previewPage" v-model:page-size="previewSize"
                         :page-sizes="[15, 30, 50, 100]" :total="previewTotal"
                         layout="total, sizes, prev, pager, next" background
                         @current-change="loadPreview" @size-change="onPreviewSizeChange" />
        </div>
      </el-tab-pane>

      <el-tab-pane label="标签" name="tags">
        <div style="display: flex; gap: 16px; align-items: center; margin-bottom: 12px">
          <el-input v-model="tagKeyword" placeholder="按标记名搜索" clearable style="width: 240px" />
          <el-select v-model="tagSourceFilter" placeholder="打标类型(全部)" clearable style="width: 160px">
            <el-option label="人工打标" value="MANUAL" />
            <el-option label="系统打标" value="SYSTEM" />
          </el-select>
          <el-button type="primary" :disabled="!dsId" style="margin-left: auto" @click="tagDialogVisible = true">打标</el-button>
        </div>
        <!-- 当前表已打标记:含系统自动维护的「空表」标记;手工打标走上方「打标」弹窗 -->
        <el-table :data="filteredTableTags" v-loading="tagsLoading" border>
          <el-table-column type="index" label="序号" width="60" />
          <el-table-column label="标记" min-width="160">
            <template #default="{ row }">
              <el-tag
                :type="row.kind === 'EMPTY' ? 'info' : undefined"
                :effect="row.kind === 'EMPTY' ? 'plain' : 'dark'"
                :color="row.kind === 'EMPTY' ? undefined : row.color"
                :style="row.kind === 'EMPTY' ? {} : { borderColor: row.color }"
              >{{ row.name }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="打标类型" width="110">
            <template #default="{ row }">
              <el-tag v-if="row.source === 'MANUAL'" size="small" type="info" effect="plain">人工打标</el-tag>
              <el-tag v-else size="small" type="success" effect="plain">系统打标</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="description" label="描述" min-width="200" show-overflow-tooltip>
            <template #default="{ row }">
              <span v-if="row.description">{{ row.description }}</span>
              <span v-else style="color: var(--el-text-color-placeholder)">-</span>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!tagsLoading && tagsLoaded && !tableTags.length"
                  description="该表还没有标记,点右上角「打标」添加" :image-size="60" />
      </el-tab-pane>

      <el-tab-pane label="ER 关系" name="er" class="er-pane">
        <!-- 推导/补充按钮已收进画布顶部工具栏;空图时画布未挂载(工具栏随画布销毁),退回页面级保证可用 -->
        <div v-if="!erGraph.nodes.length" class="er-toolbar">
          <el-button size="small" @click="inferDialogVisible = true">推导关联</el-button>
          <el-button size="small" @click="openAddRelation">手动补充</el-button>
        </div>
        <!-- 该表星型图(恒含候选边);点边看关系详情(与 ER 关系页共用抽屉组件),点表名跳对应表字段明细;ER 页签激活时画布 flex 吃满剩余高度。
             画布仅在本页签激活时挂载(el-tab-pane 非 lazy 模式只是 v-show 隐藏,不销毁——两个 G6 实例并存于 0 尺寸的
             隐藏容器里会相互干扰,表现为切回后画布错位/空白),切走即销毁、切回用缓存数据重建;字段展开态随之重置 -->
        <div class="er-canvas-wrap" v-loading="erLoading">
          <RelationGraphCanvas
            ref="erCanvasRef"
            v-if="activeTab === 'er' && erGraph.nodes.length"
            v-model:level="erLevel"
            v-model:edge-type="erEdgeType"
            :nodes="erGraph.nodes"
            :edges="erGraph.edges"
            :anchor-table="tableName"
            :columns-map="erColumnsMap"
            :max-field-rows="erMaxFieldRows"
            :field-name-mode="erFieldNameMode"
            :default-zoom="1"
            @export-drawio="exportErDrawio"
            @edge-click="onErEdgeClick"
            @node-open="goOtherTable"
          >
            <template #toolbar>
              <!-- 全部字段档单表默认展示的字段行数(超出折叠为「+N 个字段」可点击展开);localStorage 持久化 -->
              <span v-if="erLevel === 'all'" style="display: flex; align-items: center; gap: 6px">
                <span style="font-size: 12px; color: var(--el-text-color-secondary)">字段数</span>
                <el-input-number v-model="erMaxFieldRows" :min="1" :max="99" size="small"
                                 controls-position="right" style="width: 96px" @change="onErMaxFieldRowsChange" />
              </span>
              <!-- 名字口径三档(表名/字段同规则):仅中文(默认,无注释回退英文)/仅英文/中英文同时显示(中文在前) -->
              <el-select v-model="erFieldNameMode" size="small" style="width: 150px">
                <el-option label="仅显示字段中文名" value="chinese" />
                <el-option label="仅显示字段英文名" value="english" />
                <el-option label="中英文同时显示" value="both" />
              </el-select>
              <!-- 业务操作:推导关联/手动补充(排在显示类工具最后;空图时见页面级兜底) -->
              <el-button size="small" @click="inferDialogVisible = true">推导关联</el-button>
              <el-button size="small" @click="openAddRelation">手动补充</el-button>
            </template>
          </RelationGraphCanvas>
          <el-empty v-else-if="!erLoading && erLoaded"
                    description="暂无关联关系,可点上方「推导关联」推导,或「手动补充」直接添加" :image-size="60" />
        </div>
      </el-tab-pane>

      <el-tab-pane label="图谱" name="graph" class="er-pane">
        <!-- 筛选/说明收进画布顶部工具栏的 toolbar 插槽(业务工具,随画布挂载);重绘/1:1/适应画布为底座内置默认工具 -->
        <!-- 本表星型图谱(圆形节点,d3-force 力导向布局,与「ER 关系」页签同源数据);点节点开颜色面板,双击跳对应表字段明细。
             与 ER 画布一样仅在本页签激活时挂载(切走销毁、切回用缓存数据重建),保证任意时刻只有一个 G6 实例存活,互不影响 -->
        <div class="er-canvas-wrap" v-loading="graphLoading">
          <TableGraphCanvas
            v-if="activeTab === 'graph' && graphData.nodes.length"
            :nodes="graphData.nodes"
            :edges="graphData.edges"
            :anchor-table="tableName"
            layout="force"
            :colors="graphColors"
            :highlight="graphHighlight"
            :selected="graphPanelTable"
            @node-click="onGraphNodeClick"
            @node-open="goOtherTable"
            @canvas-click="graphPanelTable = ''"
          >
            <template #toolbar>
              <!-- 标记筛选:选项为当前图节点上出现过的标记;命中节点保持高亮,未命中大幅降亮度 -->
              <el-select v-model="graphFilterTags" multiple collapse-tags collapse-tags-tooltip clearable
                         placeholder="按标记筛选" size="small" style="width: 220px">
                <el-option v-for="t in graphTagOptions" :key="t.name" :label="t.name" :value="t.name">
                  <span class="graph-tag-dot" :style="{ background: t.color || 'var(--el-color-primary)' }" />{{ t.name }}
                </el-option>
              </el-select>
              <!-- 颜色筛选:选项为当前图节点实际用到的颜色(自定义色/标记色/默认色),只显示色块不显示颜色值 -->
              <el-select v-model="graphFilterColors" multiple collapse-tags collapse-tags-tooltip clearable
                         placeholder="按颜色筛选" size="small" style="width: 180px">
                <el-option v-for="c in graphColorOptions" :key="c" :label="c" :value="c">
                  <span class="graph-color-swatch" :style="{ background: c }" :title="c" />
                </el-option>
                <!-- 选中项在输入框内也只显示色块(label 槽覆盖默认的 hex 文本) -->
                <template #label="{ value }">
                  <span class="graph-color-swatch graph-color-swatch-sm" :style="{ background: value }" />
                </template>
              </el-select>
              <span style="font-size: 12px; color: var(--el-text-color-secondary)">
                节点颜色取自标记色,点节点可自定义;双击节点跳字段明细
              </span>
            </template>
          </TableGraphCanvas>
          <el-empty v-else-if="!graphLoading && graphLoaded"
                    description="暂无关联关系,可在「ER 关系」页签推导或手动补充" :image-size="60" />
          <!-- 节点点击面板:右上角悬浮卡片——表名全称(有注释时标题为注释,长注释自动换行)、字段列表(英文名/中文名/类型,
               点开面板时按表懒拉)、节点颜色行(取色器 + 标记[点标记把标记色设为节点色,空表固定排最后] + 恢复默认) -->
          <div v-if="graphPanelTable" class="graph-node-panel">
            <div class="graph-node-panel-head">
              <span class="graph-node-panel-title" :title="graphPanelComment || graphPanelTable">{{ graphPanelComment || graphPanelTable }}</span>
              <el-icon class="graph-node-panel-close" @click="graphPanelTable = ''"><Close /></el-icon>
            </div>
            <!-- 全表名恒显示,长名自动换行 -->
            <div class="graph-node-panel-sub" :title="graphPanelTable">{{ graphPanelTable }}</div>
            <!-- 字段列表(英文名/中文名/类型):点开面板时按表懒拉元数据,超高内部滚动 -->
            <div v-loading="graphPanelColumnsLoading" class="graph-node-panel-cols">
              <el-table v-if="graphPanelColumns.length" :data="graphPanelColumns" size="small" border max-height="220">
                <el-table-column prop="name" label="英文名" min-width="96" show-overflow-tooltip />
                <el-table-column prop="comment" label="中文名" min-width="84" show-overflow-tooltip>
                  <template #default="{ row }">
                    <span v-if="row.comment">{{ row.comment }}</span>
                    <span v-else style="color: var(--el-text-color-placeholder)">-</span>
                  </template>
                </el-table-column>
                <el-table-column prop="displayType" label="类型" min-width="76" show-overflow-tooltip />
              </el-table>
              <div v-else-if="!graphPanelColumnsLoading" class="graph-node-panel-cols-empty">暂无字段元数据</div>
            </div>
            <!-- 节点颜色行:取色器在最前,标记随后(空表标记固定排最后),恢复默认收尾 -->
            <div class="graph-node-panel-color">
              <el-color-picker v-model="graphPanelColor" :predefine="graphPresetColors" @change="saveGraphColor" />
              <el-tooltip v-for="t in graphPanelTagsSorted" :key="t.name" content="点击使用此标记色作为节点颜色"
                          placement="top" :show-after="200" :disabled="!t.color">
                <el-tag size="small" effect="dark" class="graph-node-tag-pick"
                        :color="t.color" :style="{ borderColor: t.color }"
                        @click="applyGraphTagColor(t)">{{ t.name }}</el-tag>
              </el-tooltip>
              <el-button size="small" text type="primary" :disabled="!graphPanelHasCustom" @click="resetGraphColor">恢复默认</el-button>
            </div>
            <div class="graph-node-panel-actions">
              <el-button size="small" @click="openTableDetailNewTab(graphPanelTable)">查看表详情</el-button>
            </div>
          </div>
        </div>
      </el-tab-pane>

    </el-tabs>

    <!-- 关系详情抽屉(与 ER 关系页同一组件);操作成功后刷新星型图 -->
    <RelationEdgeDrawer v-model="erDrawerVisible" :edge="erCurrentEdge" @changed="onErEdgeChanged" />

    <!-- 推导关联对话框(预选当前数据源+库+schema+本表,对话框自成闭环) -->
    <RelationInferDialog
      v-if="dsId && schema"
      v-model="inferDialogVisible"
      :ds-id="dsId"
      :schema="schema"
      :db="db"
      :table-name="tableName"
      @done="onInferDone"
    />

    <!-- 手动补充对话框(表清单为整库表,打开时懒拉;端一锁定当前表;保存后刷新星型图) -->
    <RelationAddDialog
      v-if="dsId && schema"
      v-model="addDialogVisible"
      :ds-id="dsId"
      :schema="schema"
      :db="db"
      :tables="addTables"
      :locked-table="tableName"
      @done="onAddDone"
    />

    <!-- 打标弹窗(复用表列表页同款组件):勾选 USER 标记 + 就地新建 -->
    <TableTagDialog
      v-model="tagDialogVisible"
      :ds-id="dsId"
      :schema="schema"
      :db="db"
      :table-name="tableName"
      :current-tags="tableTags"
      @saved="onTagsSaved"
    />
  </div>
</template>

<script setup>
import { computed, defineAsyncComponent, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from '../utils/notify'
import { Close, Refresh } from '@element-plus/icons-vue'
import request, { getRelationGraph } from '../api'
import ExportButton from '../components/ExportButton.vue'
import Breadcrumb from '../components/Breadcrumb.vue'
import SqlInput from '../components/SqlInput.vue'
import TableTagDialog from '../components/TableTagDialog.vue'
import RelationEdgeDrawer from '../components/RelationEdgeDrawer.vue'
import RelationInferDialog from '../components/RelationInferDialog.vue'
import RelationAddDialog from '../components/RelationAddDialog.vue'
import { formatDuration, formatNumber } from '../utils/format'
import { cellText, exportListToExcel } from '../utils/listExport'
import { downloadFile } from '../utils/download'
import { ensureDsName, getDsName, syncTab } from '../stores/tabs'
import { downloadDrawio } from '../utils/drawioExport'
import { exportErGraphExcel } from '../utils/erGraphExport'

const route = useRoute()
const router = useRouter()

// G6 画布异步加载:G6 体积大,切到「ER 关系」页签才拉 chunk(与 RelationGraph 页共享同一懒加载 chunk)
const RelationGraphCanvas = defineAsyncComponent(() => import('../components/RelationGraphCanvas.vue'))
// 「图谱」页签圆形节点画布(与上图共享受懒加载 chunk 依赖,不切页签不加载)
const TableGraphCanvas = defineAsyncComponent(() => import('../components/TableGraphCanvas.vue'))
const tableName = route.params.tableName
// 兼容旧路由 /scans/:jobId/tables/:tableName:数据源上下文从任务接口回填
const isScanRoute = route.path.startsWith('/scans/')

const dsId = ref(isScanRoute ? '' : route.params.id)
const schema = ref(isScanRoute ? '' : route.params.schema)
const db = ref(isScanRoute ? '' : (route.query.db || ''))
const jobId = ref(isScanRoute ? route.params.jobId : (route.query.jobId || ''))

const metaColumns = ref([])   // 结构字段(元数据)
const indexes = ref([])       // 索引结构
const statsColumns = ref([])  // 扫描统计字段(有扫描时)
const jobTable = ref(null)    // 任务中该表的统计概览
const loading = ref(false)
const indexesLoading = ref(false)
const indexesLoaded = ref(false)  // 索引是否已加载(懒加载)
const previewColumns = ref([])    // 预览列定义 [{key, name, type, comment}],key 为 c+序号 映射行数组
const previewRows = ref([])       // 预览行(原始数组,元素为字符串/null)
const previewLoading = ref(false)
const previewLoaded = ref(false)  // 预览是否已加载(懒加载)
const previewError = ref('')      // 预览加载失败的内联错误提示
const previewSize = ref(15)       // 预览每页行数(服务端分页,与全局分页组件一致可选)
const previewPage = ref(1)        // 预览当前页码
const previewTotal = ref(0)       // 预览全表总行数(COUNT(*) 实时)
const previewWhere = ref('')      // 预览过滤条件输入(WHERE,DataGrip 风格原文)
const previewOrderBy = ref('')    // 预览排序输入(ORDER BY 原文)
const ddlText = ref('')           // 建表 DDL 文本(含索引)
const ddlLoading = ref(false)
const ddlLoaded = ref(false)      // DDL 是否已加载(懒加载)
const ddlError = ref('')          // DDL 加载失败的内联错误提示
const ddlFromCache = ref(false)   // DDL 是否来自「数据源不可达降级读本地缓存」(响应头 X-Dq-Cache-Fallback)
const appliedWhere = ref('')      // 已应用的过滤条件(翻页用,输入未应用不影响)
const appliedOrderBy = ref('')    // 已应用的排序
// 过滤栏补全字段清单(复用字段明细的元数据,无需额外请求)
const completionColumns = computed(() =>
  metaColumns.value.map((c) => ({ name: c.name, type: c.displayType || '' })))
const activeTab = ref('columns')
const columnView = ref('table')   // 字段明细内视图:table=字段表格,ddl=建表 DDL
const refreshing = ref(false)
const keyword = ref('')
const onlyEmpty = ref(false)
// 当前表的人工采集状态:采集记录 id(null=未采集)
const collectId = ref(null)
const collectLoading = ref(false)
// 「标签」页签:当前表已打标记(含系统空表标记),懒加载
const tableTags = ref([])
const tagsLoading = ref(false)
const tagsLoaded = ref(false)     // 标记是否已加载(懒加载)
const tagKeyword = ref('')        // 标记名过滤
const tagSourceFilter = ref('')   // 打标类型过滤:MANUAL 人工打标 / SYSTEM 系统打标(含 AI 自动打标与空表联动),空=全部
const tagDialogVisible = ref(false)
// 「ER 关系」页签:该表星型图(恒含候选边),懒加载(首次切入才拉图数据)
const erGraph = ref({ nodes: [], edges: [] })
const erLoading = ref(false)
const erLoaded = ref(false)       // ER 图是否已加载(懒加载)
const erLevel = ref('all')         // 显示档位:name 仅表名 / related 关联字段 / all 全部字段(默认全部字段)
// erLevel=all 时各表字段清单(表名 -> [{name, type, comment}]),按库只拉一次
const erColumnsMap = ref({})
let erColumnsLoaded = false
// 全部字段档单表默认展示的字段行数(超出折叠为「+N 个字段」可点击展开),localStorage 持久化
const erMaxFieldRows = ref(Math.min(99, Math.max(1, Number(localStorage.getItem('dq-er-max-field-rows')) || 10)))
function onErMaxFieldRowsChange(v) {
  if (!Number.isFinite(v)) erMaxFieldRows.value = 10 // 清空输入失焦时回退默认
  localStorage.setItem('dq-er-max-field-rows', String(erMaxFieldRows.value))
}
// 连线线型:curve 曲线 / orth 直角 / orth-round 圆角;localStorage 持久化(与 ER 关系页同 key 共享)
const erEdgeType = ref(localStorage.getItem('dq-er-edge-type') || 'curve')
watch(erEdgeType, (v) => localStorage.setItem('dq-er-edge-type', v))
// 名字口径三档(表名/字段同规则):chinese=仅中文(默认,无注释回退英文)/english=仅英文/both=中英文同时显示(中文在前);
// 与 ER 关系页/对象管理图同名开关同语义,不持久化
const erFieldNameMode = ref('chinese')
// 点边详情抽屉(与 ER 关系页共用 RelationEdgeDrawer)
const erDrawerVisible = ref(false)
const erCurrentEdge = ref(null)
// 「推导关联」对话框(预选当前数据源+库+schema+本表)
const inferDialogVisible = ref(false)
// 「手动补充」对话框:整库表清单打开时懒拉(与表列表页同一 API),会话内缓存
const addDialogVisible = ref(false)
const addTables = ref([])
// 画布实例引用(导出 drawio 时取 G6 实测布局中心与当前档位字段行)
const erCanvasRef = ref(null)

const hasJob = computed(() => !!jobId.value)

// 空字段:有值数为 0 的字段
const emptyColumns = computed(() => columns.value.filter((c) => (c.valueCount || 0) === 0))

// 字段合并:以结构为准,扫描统计按列名附加
const columns = computed(() => {
  const statsMap = new Map(statsColumns.value.map((c) => [c.columnName, c]))
  return metaColumns.value.map((c) => ({ ...c, ...(statsMap.get(c.name) || {}) }))
})

const filteredColumns = computed(() => {
  let list = onlyEmpty.value ? emptyColumns.value : columns.value
  const kw = keyword.value.trim().toLowerCase()
  if (kw) {
    list = list.filter((c) =>
      (c.name || '').toLowerCase().includes(kw) || (c.comment || '').toLowerCase().includes(kw))
  }
  return list
})

// 标签页签筛选:名称关键字 + 打标类型(source:MANUAL=人工打标,其余=系统打标)
const filteredTableTags = computed(() => {
  let list = tableTags.value
  const kw = tagKeyword.value.trim().toLowerCase()
  if (kw) list = list.filter((t) => (t.name || '').toLowerCase().includes(kw))
  if (tagSourceFilter.value) {
    list = list.filter((t) => tagSourceFilter.value === 'MANUAL'
      ? t.source === 'MANUAL'
      : t.source !== 'MANUAL')
  }
  return list
})

/** 打标类型展示文案:人工打标(手动勾选)/ 系统打标(AI 自动打标、空表联动) */
function tagSourceLabel(tag) {
  return tag.source === 'MANUAL' ? '人工打标' : '系统打标'
}

// 键约束展示:PK / UNI / 空(结构推导优先,兼容扫描接口旧 keyLabel)
function keyLabel(row) {
  if (row.primaryKey) return 'PK'
  if (row.uniqueIndexFirst) return 'UNI'
  return row.keyLabel || ''
}

function nullTotal(row) {
  return (row.nullCount || 0) + (row.emptyCount || 0) + (row.ruleHitCount || 0)
}

function sortByNullTotal(a, b) {
  return nullTotal(a) - nullTotal(b)
}

async function load(refresh = false) {
  loading.value = true
  try {
    // 旧扫描路由 / 表路由带 jobId:先取任务信息(拿数据源上下文 + 该表统计概览)
    if (isScanRoute || jobId.value) {
      const job = await request.get(`/scans/${jobId.value}`).catch(() => null)
      if (job) {
        if (isScanRoute) {
          dsId.value = job.datasourceId
          schema.value = job.schemaName
          db.value = job.dbName || ''
        }
        jobTable.value = job.tables?.find((t) => t.tableName === tableName) || null
      }
    }
    if (!dsId.value || !schema.value) return

    const base = `/datasources/${dsId.value}/schemas/${encodeURIComponent(schema.value)}`
    const params = new URLSearchParams()
    if (db.value) params.set('db', db.value)
    if (refresh) params.set('refresh', 'true')
    const q = params.toString() ? `?${params.toString()}` : ''
    const url = `${base}/tables/${encodeURIComponent(tableName)}`

    // 结构 + 扫描统计并行拉取(索引懒加载,切到索引 tab 时才请求)
    const [cols, stats] = await Promise.all([
      request.get(`${url}/columns${q}`),
      jobId.value
        ? request.get(`/scans/${jobId.value}/tables/${encodeURIComponent(tableName)}/columns`).catch(() => [])
        : Promise.resolve([])
    ])
    metaColumns.value = cols
    statsColumns.value = stats || []
  } finally {
    loading.value = false
  }
}

/** 拉取当前表采集状态(本库采集 map 里按表名取记录 id),失败静默按未采集处理 */
async function loadCollectStatus() {
  if (!dsId.value || !schema.value) return
  const base = `/datasources/${dsId.value}/schemas/${encodeURIComponent(schema.value)}`
  const q = db.value ? `?db=${encodeURIComponent(db.value)}` : ''
  const map = await request.get(`${base}/manual-collects${q}`).catch(() => ({}))
  collectId.value = map?.[tableName] ?? null
}

/** 采集/取消采集切换:取消按记录 id 删除,采集走批量接口(单表即一项);本页无表注释,快照传 null */
async function toggleCollect() {
  collectLoading.value = true
  try {
    if (collectId.value) {
      await request.delete(`/manual-collects/${collectId.value}`)
      collectId.value = null
      ElMessage.success(`已取消采集「${tableName}」`)
    } else {
      await request.post('/manual-collects', {
        items: [{
          datasourceId: /^\d+$/.test(String(dsId.value)) ? Number(dsId.value) : dsId.value,
          dbName: db.value || null,
          schemaName: schema.value,
          tableName,
          tableComment: null
        }]
      })
      ElMessage.success(`已采集「${tableName}」,可在「人工采集」页签查看`)
      await loadCollectStatus()
    }
  } finally {
    collectLoading.value = false
  }
}

/** 标签页签:拉本库打标 map 取当前表 entry,失败静默按无标记处理 */
async function loadTags() {
  if (!dsId.value || !schema.value) return
  tagsLoading.value = true
  try {
    const base = `/datasources/${dsId.value}/schemas/${encodeURIComponent(schema.value)}`
    const q = db.value ? `?db=${encodeURIComponent(db.value)}` : ''
    const map = await request.get(`${base}/table-tags${q}`).catch(() => ({}))
    tableTags.value = map?.[tableName] || []
    tagsLoaded.value = true
  } finally {
    tagsLoading.value = false
  }
}

/** 打标保存:接口返回该表最新标记数组(含空表标记),就地回填 */
function onTagsSaved(tags) {
  tableTags.value = tags
}

/** 索引懒加载:首次切到索引 tab 或刷新时调用,已加载则跳过(除非强制) */
async function loadIndexes(force = false) {
  if (indexesLoaded.value && !force) return
  if (!dsId.value || !schema.value) return
  indexesLoading.value = true
  try {
    const base = `/datasources/${dsId.value}/schemas/${encodeURIComponent(schema.value)}`
    const params = new URLSearchParams()
    if (db.value) params.set('db', db.value)
    if (force) params.set('refresh', 'true')
    const q = params.toString() ? `?${params.toString()}` : ''
    const url = `${base}/tables/${encodeURIComponent(tableName)}`
    indexes.value = await request.get(`${url}/indexes${q}`).catch(() => [])
    indexesLoaded.value = true
  } finally {
    indexesLoading.value = false
  }
}

/** DDL 懒加载:首次切到 DDL 视图或刷新时调用;本地缓存优先,refresh=true 强制回源覆盖 */
async function loadDdl(refresh = false) {
  if (!dsId.value || !schema.value) return
  ddlLoading.value = true
  ddlError.value = ''
  ddlFromCache.value = false
  try {
    const base = `/datasources/${dsId.value}/schemas/${encodeURIComponent(schema.value)}`
    const params = new URLSearchParams()
    if (db.value) params.set('db', db.value)
    if (refresh) params.set('refresh', 'true')
    const q = params.toString() ? `?${params.toString()}` : ''
    // 取原始响应读降级响应头:数据源不可达时后端返回本地缓存 DDL
    const resp = await request.get(`${base}/tables/${encodeURIComponent(tableName)}/ddl${q}`, { _raw: true })
    ddlFromCache.value = resp.headers?.['x-dq-cache-fallback'] === 'true'
    ddlText.value = resp.data?.ddl || ''
    ddlLoaded.value = true
  } catch (e) {
    // 拦截器已弹出错误消息,这里留内联提示;不置 loaded,允许重试
    ddlError.value = e?.response?.data?.message || e?.message || '加载 DDL 失败'
  } finally {
    ddlLoading.value = false
  }
}

// 预览行数组转成 el-table 需要的行对象(c0/c1/... 与列定义 key 对应)
const previewTableData = computed(() =>
  previewRows.value.map((r) => Object.fromEntries(r.map((v, i) => ['c' + i, v]))))

/** 数据预览:服务端分页,切页/首次切入/刷新时按页拉取;失败保留错误提示,可再次切换/刷新重试 */
async function loadPreview(page = previewPage.value || 1) {
  if (!dsId.value || !schema.value) return
  previewLoading.value = true
  previewError.value = ''
  try {
    const base = `/datasources/${dsId.value}/schemas/${encodeURIComponent(schema.value)}`
    const params = new URLSearchParams()
    if (db.value) params.set('db', db.value)
    if (appliedWhere.value) params.set('where', appliedWhere.value)
    if (appliedOrderBy.value) params.set('orderBy', appliedOrderBy.value)
    params.set('page', String(page))
    params.set('size', String(previewSize.value))
    const url = `${base}/tables/${encodeURIComponent(tableName)}/preview?${params.toString()}`
    const data = await request.get(url)
    previewColumns.value = (data.columns || []).map((c, i) => ({ key: 'c' + i, name: c.name, type: c.type, comment: c.comment || '' }))
    previewRows.value = data.rows || []
    previewTotal.value = data.total ?? 0
    previewPage.value = data.page ?? page
    previewLoaded.value = true
  } catch (e) {
    // 拦截器已弹出错误消息,这里留内联提示;不置 loaded,允许重试
    previewError.value = e?.response?.data?.message || e?.message || '加载预览数据失败'
  } finally {
    previewLoading.value = false
  }
}

/** 切换每页条数:回到第 1 页重新查询 */
function onPreviewSizeChange() {
  loadPreview(1)
}

/** 复制文本到剪贴板:内网 http 部署是非安全上下文,没有 Clipboard API,退回隐藏 textarea 方案 */
async function copyText(text, successMsg) {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
    } else {
      const ta = document.createElement('textarea')
      ta.value = text
      ta.style.position = 'fixed'
      ta.style.opacity = '0'
      document.body.appendChild(ta)
      ta.select()
      document.execCommand('copy')
      ta.remove()
    }
    ElMessage.success(successMsg)
  } catch {
    ElMessage.error('复制失败')
  }
}

/** 点击预览单元格复制完整内容(长文本截断成 ... 时靠它取全文);NULL/序号列不复制 */
async function copyPreviewCell(row, column) {
  const v = row[column.property]
  if (v === null || v === undefined) return
  copyText(String(v), '已复制单元格内容')
}

/** 复制整段建表 DDL */
function copyDdl() {
  copyText(ddlText.value, '已复制 DDL')
}

/** 应用过滤/排序:同步到已应用变量并回到第 1 页重新查询 */
function applyPreviewFilter() {
  appliedWhere.value = previewWhere.value.trim()
  appliedOrderBy.value = previewOrderBy.value.trim()
  loadPreview(1)
}

/** 切换 tab:切到索引/预览/标签时各自懒加载(预览仅首次,之后翻页由分页器触发) */
function onTabChange(name) {
  if (name === 'indexes') loadIndexes()
  if (name === 'preview' && !previewLoaded.value) loadPreview(1)
  if (name === 'tags' && !tagsLoaded.value) loadTags()
  if (name === 'er') loadErGraph()
  if (name === 'graph') loadGraph()
}

// ---------- 「ER 关系」页签(该表星型图,懒加载) ----------

/** 拉星型图数据(table 参数=本表,恒含候选边);force 强制重拉(刷新/抽屉内状态变更后) */
async function loadErGraph(force = false) {
  if (!dsId.value || !schema.value) return
  if (erLoaded.value && !force) return
  erLoading.value = true
  try {
    // 默认档位为「全部字段」:先拉整库字段清单,首帧即带字段行(内部去重,整库只拉一次);
    // watch(erLevel) 对初始默认值不触发,故在此主动补拉
    if (erLevel.value === 'all') await loadErColumnsMap()
    erGraph.value = await getRelationGraph({
      datasourceId: dsId.value,
      dbName: db.value || undefined,
      schemaName: schema.value,
      table: tableName,
      includeCandidate: true
    })
    erLoaded.value = true
  } finally {
    erLoading.value = false
  }
}

/** erLevel=all 时拉整库字段清单并按表分组(与 ER 关系页同一接口,只拉一次) */
async function loadErColumnsMap() {
  if (erColumnsLoaded || !dsId.value || !schema.value) return
  erColumnsLoaded = true
  const base = `/datasources/${dsId.value}/schemas/${encodeURIComponent(schema.value)}`
  const q = db.value ? `?db=${encodeURIComponent(db.value)}` : ''
  const list = await request.get(`${base}/columns${q}`).catch(() => [])
  const map = {}
  for (const c of list || []) {
    if (!c || !c.table || !c.name) continue
    if (!map[c.table]) map[c.table] = []
    map[c.table].push({ name: c.name, type: c.type || '', comment: c.comment || '' })
  }
  erColumnsMap.value = map
}

watch(erLevel, (v) => {
  if (v === 'all') loadErColumnsMap()
})


/** 导出星型图 .drawio:与 ER 关系页同一导出工具(档位所见即所得,前端拼装 mxfile XML 下载) */
function exportErDrawio() {
  const data = erCanvasRef.value?.exportData()
  if (!data || !data.nodes.length) return ElMessage.warning('当前图没有可导出的节点')
  downloadDrawio(data, `ER 星型图-${schema.value}-${tableName}`)
}

/** 「ER 关系」页签导出 Excel(工具栏「导出ER表格」):与 ER 关系页「导出ER关系」同口径,
 *  行 = 星型图内每张表 × 该表作为关系端点的去重级联字段,逻辑见 erGraphExport.js;
 *  字段注释清单可能未加载(仅 erLevel=all 时才拉),导出前先确保已加载 */
const erExporting = ref(false)
async function exportErExcel() {
  if (!erGraph.value.nodes.length) return ElMessage.warning('当前图没有可导出的数据')
  erExporting.value = true
  try {
    await loadErColumnsMap()
    await exportErGraphExcel({
      dsId: dsId.value,
      db: db.value,
      schema: schema.value,
      filename: `ER 星型图-${schema.value}-${tableName}`,
      graph: erGraph.value,
      columnsMap: erColumnsMap.value
    })
  } finally {
    erExporting.value = false
  }
}
/** query tab=er 时激活「ER 关系」页签(推导对话框 DONE「查看」的跳转落点);
 *  激活即触发懒加载(loadErGraph 内部已加载守卫不重复拉);无该参数行为不变 */
function applyQueryTab() {
  if (route.query.tab !== 'er') return
  activeTab.value = 'er'
  loadErGraph()
}
// keep-alive 复用时带 tab 参数新 push 进来(fullPath 变化通常伴随重挂载,watch 兜住同挂载内 query 变化的场景)
watch(() => route.query.tab, applyQueryTab)

/** 点边:打开关系详情抽屉(确认/否决/删除由抽屉组件承载,changed 后刷新星型图) */
function onErEdgeClick(rel) {
  erCurrentEdge.value = rel
  erDrawerVisible.value = true
}

/** 抽屉内操作成功:确认就地更新状态标签并整图回源刷新;
 *  否决/删除的边不再进图——先更新内存中的关系数据(剔除该边;星型图节点口径=锚点+边两端,
 *  失去全部连线的邻表节点一并摘除,与服务端 graph() 口径一致),画布 watch 到变化后整体重绘(不回源重拉) */
function onErEdgeChanged({ action }) {
  if (action === 'confirm') {
    if (erCurrentEdge.value) erCurrentEdge.value = { ...erCurrentEdge.value, status: 'CONFIRMED' }
    loadErGraph(true)
    return
  }
  const id = erCurrentEdge.value?.id
  if (id == null) return
  const edges = (erGraph.value.edges || []).filter((e) => e.id !== id)
  const keep = new Set([tableName])
  for (const e of edges) { keep.add(e.oneTable); keep.add(e.manyTable) }
  const nodes = (erGraph.value.nodes || []).filter((n) => keep.has(n.name))
  erGraph.value = { ...erGraph.value, nodes, edges }
}

/** 点节点表名:跳对应表字段明细(本表即当前页,路由相同不跳转) */
function goOtherTable(table) {
  if (table === tableName) return
  const base = `/datasources/${dsId.value}/schemas/${encodeURIComponent(schema.value)}/tables/${encodeURIComponent(table)}`
  router.push(db.value ? `${base}?db=${encodeURIComponent(db.value)}` : base)
}

/** 推导完成:提示候选数;ER 页签已加载则刷新星型图 */
async function onInferDone(job) {
  ElMessage.success(`推导完成,发现 ${job.foundCount} 条候选关系`)
  if (erLoaded.value) await loadErGraph(true)
}

/** 打开手动补充:懒拉整库表清单(失败由拦截器提示,对话框仍可用手输不了表名则等下次) */
async function openAddRelation() {
  addDialogVisible.value = true
  if (addTables.value.length) return
  try {
    const base = `/datasources/${dsId.value}/schemas/${encodeURIComponent(schema.value)}/tables`
    const q = db.value ? `?db=${encodeURIComponent(db.value)}` : ''
    const list = await request.get(base + q)
    addTables.value = (list || []).map((t) => t.name)
  } catch { /* 单次失败维持空清单,下次打开重试 */ }
}

/** 手动补充完成:命中唯一键的已存在关系后端转 CONFIRMED 返回原 id(existing=true) */
async function onAddDone(res) {
  if (res?.existing) ElMessage.success('该关系已存在,已转为确认')
  else ElMessage.success('已添加确认关系')
  await loadErGraph(true)
}

// ---------- 「图谱」页签(圆形节点星型图,懒加载;与「ER 关系」页签同一图数据) ----------

// 图数据与加载标记(懒加载,首次切入才拉)
const graphData = ref({ nodes: [], edges: [] })
const graphLoading = ref(false)
const graphLoaded = ref(false)
// 本库打标 map(表名 -> 标记数组,与「标签」页签同一接口),节点取首个标记色
const graphTagsMap = ref({})
// 筛选:标记多选 / 颜色多选;任一维度有选中即生效,命中节点高亮、未命中降亮度
const graphFilterTags = ref([])
const graphFilterColors = ref([])
// 节点点击面板(自定义颜色)
const graphPanelTable = ref('')
const graphPanelColor = ref('')
// 面板字段列表(点开面板时按表懒拉元数据,与字段明细页同一接口)
const graphPanelColumns = ref([])
const graphPanelColumnsLoading = ref(false)
// 颜色选择器预设色板(与标记新建表单 TagCreateForm 同一套)
const graphPresetColors = ['#409EFF', '#67C23A', '#E6A23C', '#F56C6C', '#909399', '#9B59B6', '#16A085', '#D35400']
// 自定义节点颜色:{ "dsId|db|schema|table": "#hex" },localStorage 持久化(本机偏好,同侧边栏宽度/主题口径)
const GRAPH_COLOR_KEY = 'dq-graph-node-colors'
const graphCustomColors = ref(loadGraphCustomColors())

function loadGraphCustomColors() {
  try {
    const obj = JSON.parse(localStorage.getItem(GRAPH_COLOR_KEY) || '{}')
    return obj && typeof obj === 'object' ? obj : {}
  } catch {
    return {}
  }
}

function persistGraphColors() {
  localStorage.setItem(GRAPH_COLOR_KEY, JSON.stringify(graphCustomColors.value))
}

/** 自定义颜色的存储键:四元组(数据源|库|schema|表) */
function graphColorKey(table) {
  return `${dsId.value}|${db.value}|${schema.value}|${table}`
}

/** 主题色兜底(默认节点色):跟随亮/暗主题 */
function graphDefaultColor() {
  return getComputedStyle(document.documentElement).getPropertyValue('--el-color-primary').trim() || '#409eff'
}

/** 节点颜色解析:自定义 > 首个标记色 > 主题默认色 */
function graphNodeColor(table) {
  const custom = graphCustomColors.value[graphColorKey(table)]
  if (custom) return custom
  const tags = graphTagsMap.value[table] || []
  return tags[0]?.color || graphDefaultColor()
}

/** 各节点已解析颜色:{ 表名: '#hex' },画布直接消费 */
const graphColors = computed(() => {
  const m = {}
  for (const n of graphData.value.nodes || []) m[n.name] = graphNodeColor(n.name)
  return m
})

/** 标记筛选项:当前图节点上出现过的标记(去重,带颜色) */
const graphTagOptions = computed(() => {
  const map = new Map()
  for (const n of graphData.value.nodes || []) {
    for (const t of graphTagsMap.value[n.name] || []) {
      if (t?.name && !map.has(t.name)) map.set(t.name, t.color || '')
    }
  }
  return [...map.entries()].map(([name, color]) => ({ name, color })).sort((a, b) => a.name.localeCompare(b.name))
})

/** 颜色筛选项:当前图节点实际用到的颜色(去重) */
const graphColorOptions = computed(() =>
  [...new Set((graphData.value.nodes || []).map((n) => graphNodeColor(n.name)))])

/** 筛选命中的表名数组:null=无筛选;维度内 OR、维度间 AND */
const graphHighlight = computed(() => {
  const tagSel = graphFilterTags.value
  const colorSel = graphFilterColors.value
  if (!tagSel.length && !colorSel.length) return null
  const names = []
  for (const n of graphData.value.nodes || []) {
    const tagOk = !tagSel.length || (graphTagsMap.value[n.name] || []).some((t) => tagSel.includes(t.name))
    const colorOk = !colorSel.length || colorSel.includes(graphNodeColor(n.name))
    if (tagOk && colorOk) names.push(n.name)
  }
  return names
})

const graphPanelComment = computed(() =>
  (graphData.value.nodes || []).find((n) => n.name === graphPanelTable.value)?.comment || '')
const graphPanelTags = computed(() => graphTagsMap.value[graphPanelTable.value] || [])
/** 面板标记排序:空表标记(系统维护,kind=EMPTY)固定排最后,其余保持原顺序 */
const graphPanelTagsSorted = computed(() =>
  [...graphPanelTags.value].sort((a, b) => Number(a.kind === 'EMPTY') - Number(b.kind === 'EMPTY')))
const graphPanelHasCustom = computed(() => !!graphCustomColors.value[graphColorKey(graphPanelTable.value)])

/** 拉图谱数据(table 参数=本表,恒含候选边;与 ER 页签同接口)+ 本库打标 map;force 强制重拉 */
async function loadGraph(force = false) {
  if (!dsId.value || !schema.value) return
  if (graphLoaded.value && !force) return
  graphLoading.value = true
  try {
    const base = `/datasources/${dsId.value}/schemas/${encodeURIComponent(schema.value)}`
    const q = db.value ? `?db=${encodeURIComponent(db.value)}` : ''
    const [g, tags] = await Promise.all([
      getRelationGraph({
        datasourceId: dsId.value,
        dbName: db.value || undefined,
        schemaName: schema.value,
        table: tableName,
        includeCandidate: true
      }),
      request.get(`${base}/table-tags${q}`).catch(() => ({}))
    ])
    graphData.value = g
    graphTagsMap.value = tags || {}
    graphLoaded.value = true
  } finally {
    graphLoading.value = false
  }
}

/** 点节点:开颜色面板,取色器回填当前已解析颜色,并懒拉该表字段列表 */
function onGraphNodeClick(table) {
  graphPanelTable.value = table
  graphPanelColor.value = graphNodeColor(table)
  loadGraphPanelColumns(table)
}

/** 面板字段列表:按表拉元数据字段(与字段明细页同一接口);连点不同节点时只回填最后一次点击的表 */
async function loadGraphPanelColumns(table) {
  graphPanelColumns.value = []
  graphPanelColumnsLoading.value = true
  try {
    const base = `/datasources/${dsId.value}/schemas/${encodeURIComponent(schema.value)}/tables/${encodeURIComponent(table)}/columns`
    const q = db.value ? `?db=${encodeURIComponent(db.value)}` : ''
    const cols = await request.get(base + q).catch(() => [])
    if (graphPanelTable.value === table) graphPanelColumns.value = cols || []
  } finally {
    if (graphPanelTable.value === table) graphPanelColumnsLoading.value = false
  }
}

/** 面板「查看表详情」:新页签打开该表字段明细(query newTab 让页签解析为独占页签,不顶替本页签下钻) */
function openTableDetailNewTab(table) {
  if (!table) return
  const base = `/datasources/${dsId.value}/schemas/${encodeURIComponent(schema.value)}/tables/${encodeURIComponent(table)}`
  const params = new URLSearchParams()
  if (db.value) params.set('db', db.value)
  params.set('newTab', '1')
  router.push(`${base}?${params.toString()}`)
}

/** 取色器选定即保存(写 localStorage),画布随 graphColors computed 重建 */
function saveGraphColor(color) {
  if (!color || !graphPanelTable.value) return
  graphCustomColors.value = { ...graphCustomColors.value, [graphColorKey(graphPanelTable.value)]: color }
  persistGraphColors()
}

/** 恢复默认:清掉自定义色,回落到标记色/主题色 */
function resetGraphColor() {
  const m = { ...graphCustomColors.value }
  delete m[graphColorKey(graphPanelTable.value)]
  graphCustomColors.value = m
  persistGraphColors()
  graphPanelColor.value = graphNodeColor(graphPanelTable.value)
}

/** 面板内点标记:把该标记色设为节点颜色(等同取色器选色,写 localStorage) */
function applyGraphTagColor(tag) {
  if (!tag?.color) return
  graphPanelColor.value = tag.color
  saveGraphColor(tag.color)
}

/** 字段明细内 表格/DDL 切换:首次切到 DDL 时懒加载;切回表格时表格重挂载,重算视口高度 */
function onColumnViewChange(view) {
  if (view === 'ddl' && !ddlLoaded.value) loadDdl()
}

/** 导出当前 tab 的列表 Excel(字段明细/索引结构/标签为所见数据;ER 关系为星型图表格;数据预览走服务端全量导出) */
async function exportExcel() {
  if (activeTab.value === 'er') {
    await exportErExcel()
    return
  }
  if (activeTab.value === 'columns') {
    if (columnView.value === 'ddl') return ElMessage.warning('DDL 请使用「复制 DDL」按钮')
    if (!filteredColumns.value.length) return ElMessage.warning('当前列表没有可导出的数据')
    const headers = ['字段名', '注释', '类型', '键', '可空', '默认值']
    if (hasJob.value) headers.push('空值数(合计)', '总行数', 'NULL 数', '空串数', '有值数', '有值率%')
    const rows = filteredColumns.value.map((c) => {
      const row = [
        cellText(c.name),
        cellText(c.comment),
        cellText(c.displayType),
        keyLabel(c),
        c.nullable === null || c.nullable === undefined ? '' : (c.nullable ? '是' : '否'),
        cellText(c.defaultValue)
      ]
      if (hasJob.value) {
        row.push(
          formatNumber(nullTotal(c)),
          formatNumber(c.totalRows),
          formatNumber(c.nullCount),
          formatNumber(c.emptyCount),
          formatNumber(c.valueCount),
          c.fillRate === null || c.fillRate === undefined ? '' : String(Math.round(c.fillRate * 100) / 100)
        )
      }
      return row
    })
    exportListToExcel(`字段明细-${tableName}`, headers, rows, '字段明细')
  } else if (activeTab.value === 'indexes') {
    if (!indexes.value.length) return ElMessage.warning('当前列表没有可导出的数据')
    const headers = ['索引名', '唯一', '索引列']
    const rows = indexes.value.map((i) => [
      cellText(i.name),
      i.unique ? '唯一' : '',
      (i.columns || []).join(', ')
    ])
    exportListToExcel(`索引结构-${tableName}`, headers, rows, '索引结构')
  } else if (activeTab.value === 'tags') {
    if (!filteredTableTags.value.length) return ElMessage.warning('当前列表没有可导出的数据')
    const headers = ['标记名', '打标类型', '描述']
    const rows = filteredTableTags.value.map((t) => [cellText(t.name), tagSourceLabel(t), cellText(t.description)])
    exportListToExcel(`标签-${tableName}`, headers, rows, '标签')
  } else {
    // 数据预览:服务端流式导出全部符合条件数据(已应用的 WHERE/ORDER BY,上限 20 万行)
    if (!previewRows.value.length) return ElMessage.warning('当前列表没有可导出的数据')
    const base = `/api/datasources/${dsId.value}/schemas/${encodeURIComponent(schema.value)}/tables/${encodeURIComponent(tableName)}/preview/export`
    const params = new URLSearchParams()
    if (db.value) params.set('db', db.value)
    if (appliedWhere.value) params.set('where', appliedWhere.value)
    if (appliedOrderBy.value) params.set('orderBy', appliedOrderBy.value)
    const q = params.toString()
    downloadFile(q ? `${base}?${q}` : base)
  }
}

/** 手动刷新:结构强制从业务库拉最新并覆盖本地缓存,统计一并重拉;索引/预览/DDL 按当前展示位置决定是否重载 */
async function refreshAll() {
  refreshing.value = true
  try {
    // 重置索引/预览/DDL/标签/ER图/图谱 加载标记,若当前正在展示则强制重新拉取
    indexesLoaded.value = false
    previewLoaded.value = false
    ddlLoaded.value = false
    tagsLoaded.value = false
    erLoaded.value = false
    graphLoaded.value = false
    graphPanelTable.value = ''
    await load(true)
    await loadCollectStatus()
    if (activeTab.value === 'indexes') await loadIndexes(true)
    if (activeTab.value === 'preview') await loadPreview(previewPage.value)
    if (activeTab.value === 'tags') await loadTags()
    if (activeTab.value === 'er') await loadErGraph(true)
    if (activeTab.value === 'graph') await loadGraph(true)
    if (activeTab.value === 'columns' && columnView.value === 'ddl') await loadDdl(true)
    ElMessage.success('已刷新结构与扫描信息')
  } finally {
    refreshing.value = false
  }
}

// ---------- 面包屑 ----------
const dsName = computed(() => getDsName(dsId.value) || `数据源 ${dsId.value}`)
const schemaLabel = computed(() => (db.value ? `${db.value}.${schema.value}` : schema.value))
const tablesPath = computed(() => {
  const base = `/datasources/${dsId.value}/schemas/${encodeURIComponent(schema.value)}/tables`
  return db.value ? `${base}?db=${encodeURIComponent(db.value)}` : base
})
const breadcrumbItems = computed(() => {
  const items = [
    { label: dsName.value, to: `/datasources/${dsId.value}/schemas` },
    { label: schemaLabel.value, to: tablesPath.value }
  ]
  if (hasJob.value) items.push({ label: `扫描 #${jobId.value}`, to: `/scans/${jobId.value}` })
  items.push({ label: tableName })
  return items
})

onMounted(async () => {
  await load()
  loadCollectStatus()
  // query tab=er(推导对话框「查看」跳转):定位激活「ER 关系」页签(须在 load 后,dsId/schema 可能由任务接口回填)
  applyQueryTab()
  // 数据源名兜底解析:刷新/直达 URL 无 ?name= 时也能恢复真名,并刷新页签标题
  // (dsId 在 load 内由任务接口回填,须在 load 之后解析)
  if (dsId.value) ensureDsName(dsId.value).then(() => syncTab(route))
})
</script>

<style scoped>

/* 表头吸顶适配:全局 sticky 表头(style.css)要求祖先链无 overflow 裁剪,
   EP 的 .el-tabs__content 默认 overflow:hidden 会打断,本页页签内容放开;
   非激活页签为 display:none,放开不影响其余页签 */
:deep(.el-tabs__content) {
  overflow: visible;
}
.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  padding-top: 12px;
}

/* 数据预览表格:正文与表头用一级文字色,提高对比度(NULL/类型标注仍用占位色弱化) */
.preview-table {
  --el-table-text-color: var(--el-text-color-primary);
  --el-table-header-text-color: var(--el-text-color-primary);
}

/* 数据预览表头:三行依次为 字段名 / 类型 / 注释;后两行次级小字,
   注释(或缺失占位 —)超长省略,悬停 title 看全文 */
.preview-table .pv-h-name {
  line-height: 1.5;
}
.preview-table .pv-h-sub {
  font-size: 12px;
  font-weight: normal;
  color: var(--el-text-color-placeholder);
  line-height: 1.5;
  margin-top: 2px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 单元格点击可复制,用 copy 光标提示可交互 */
.preview-table :deep(.el-table__cell) {
  cursor: copy;
}

/* 建表 DDL 文本视图:等宽字体,长行自动换行 */
.ddl-view {
  margin: 0;
  padding: 12px 16px;
  background: var(--el-fill-color-light);
  border-radius: 4px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
}

/* ER 页签激活时整卡铺满 .main 可视高度(同 Logs/SqlConsole 口径:100% 只减 page-card 上下 margin,
   授权页脚/页签栏高度天然计入),页签区 flex 纵向撑满、画布吃剩余高度——不再溢出滚动条遮住图例 */
.page-card.er-fullheight {
  display: flex;
  flex-direction: column;
  height: calc(100% - 40px);
  box-sizing: border-box;
  overflow: auto;
}
.er-fullheight :deep(.el-tabs) {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.er-fullheight :deep(.el-tabs__content) {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}
.er-fullheight :deep(.er-pane) {
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: auto;
}
/* ER 页签工具栏:紧凑统一间距;消除 el-button 相邻默认 margin-left,交给 flex gap */
.er-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}
.er-toolbar .el-button + .el-button {
  margin-left: 0;
}
/* ER 页签:星型图画布(flex 吃满页签剩余高度) */
.er-canvas-wrap {
  position: relative;
  flex: 1;
  min-height: 320px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  overflow: hidden;
}
/* 图谱页签:筛选下拉选项内的颜色圆点 */
.graph-tag-dot {
  display: inline-block;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  margin-right: 6px;
  vertical-align: middle;
}
/* 图谱页签:颜色筛选项的色块(下拉选项内大块,输入框选中标签内小块) */
.graph-color-swatch {
  display: inline-block;
  width: 48px;
  height: 14px;
  border-radius: 3px;
  border: 1px solid var(--el-border-color);
  vertical-align: middle;
}
.graph-color-swatch-sm {
  width: 24px;
  height: 12px;
}
/* 图谱页签:面板内可点击的标记(点击把标记色设为节点色) */
.graph-node-tag-pick {
  cursor: pointer;
}
/* 图谱页签:节点点击面板(画布右上角悬浮卡片,与 ER 关系页 node-panel 同款);
   半透明毛玻璃底与工具栏缩放控制条同口径(55% 底色 + 12px 背景模糊),图元素压到面板下不挡阅读 */
.graph-node-panel {
  position: absolute;
  /* 顶部工具栏(约 44px)之下,避免压住右侧缩放控制条 */
  top: 52px;
  right: 12px;
  width: 360px;
  background: color-mix(in srgb, var(--el-bg-color) 55%, transparent);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  box-shadow: var(--el-box-shadow-light);
  padding: 12px;
  z-index: 10;
}
/* 面板内字段表格去底色:el-table 默认白底会盖住毛玻璃,表格各行/表头/单元格全部透明 */
.graph-node-panel :deep(.el-table),
.graph-node-panel :deep(.el-table__inner-wrapper),
.graph-node-panel :deep(.el-table tr),
.graph-node-panel :deep(.el-table th.el-table__cell),
.graph-node-panel :deep(.el-table td.el-table__cell) {
  background: transparent;
}
/* 透明底下默认边框色对比度不够(图元素透到背面更看不清),边框/表头文字各加深一档 */
.graph-node-panel :deep(.el-table) {
  --el-table-border-color: var(--el-border-color-darker);
  --el-table-header-text-color: var(--el-text-color-primary);
}
.graph-node-panel-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 8px;
}
/* 标题(中文注释优先)完整显示:长文本自动换行,不截断 */
.graph-node-panel-title {
  font-weight: 600;
  word-break: break-all;
  line-height: 1.4;
}
.graph-node-panel-close {
  cursor: pointer;
  color: var(--el-text-color-secondary);
  flex-shrink: 0;
  margin-top: 2px;
}
.graph-node-panel-sub {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  word-break: break-all;
}
/* 字段列表区:固定最小高度承载 loading,空数据居中提示;单元格 padding 收紧让三列更紧凑 */
.graph-node-panel-cols {
  margin-top: 10px;
  min-height: 40px;
}
.graph-node-panel-cols :deep(.el-table .el-table__cell) {
  padding: 4px 0;
}
.graph-node-panel-cols :deep(.el-table .cell) {
  padding: 0 6px;
  line-height: 1.4;
}
.graph-node-panel-cols-empty {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
  text-align: center;
  padding: 12px 0;
}
/* 节点颜色行:取色器 + 标记 + 恢复默认同行,标记多了自动换行 */
.graph-node-panel-color {
  margin-top: 10px;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
}
.graph-node-panel-actions {
  margin-top: 10px;
}
</style>
