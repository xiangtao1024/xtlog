package top.xtcoder.xtlog.controller;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import top.xtcoder.xtboot.context.annotation.Autowired;
import top.xtcoder.xtboot.core.Dict;
import top.xtcoder.xtboot.orm.DbClientHolder;
import top.xtcoder.xtboot.web.annotation.DeleteMapping;
import top.xtcoder.xtboot.web.annotation.GetMapping;
import top.xtcoder.xtboot.web.annotation.PathVariable;
import top.xtcoder.xtboot.web.annotation.PostMapping;
import top.xtcoder.xtboot.web.annotation.PutMapping;
import top.xtcoder.xtboot.web.annotation.RequestBody;
import top.xtcoder.xtboot.web.annotation.RequestParam;
import top.xtcoder.xtboot.web.annotation.RestController;
import top.xtcoder.xtboot.auth.annotation.SaCheckLogin;
import top.xtcoder.xtboot.web.model.ApiResponse;

import top.xtcoder.xtlog.service.AppService;

/**
 * 应用管理：新建/查询/修改/删除。新建时自动生成接入 token，并刷新 AppService 索引。
 */
@RestController
@SaCheckLogin
public class AppController {

    @Autowired
    private DbClientHolder holder;

    @Autowired
    private AppService appService;

    /**
     * 应用列表（分页 + 关键词 + 分类筛选）。
     *
     * <p>返回 {@code {list,total,page,pageSize}}：前端表格要分页，就不能继续裸返回数组。
     * 关键词同时匹配名称与备注，LIKE 的转义交给参数绑定，不做字符串拼接。</p>
     */
    @GetMapping("/api/apps")
    public Object list(@RequestParam(value = "keyword", required = false) String keyword,
                       @RequestParam(value = "categoryId", required = false) String categoryId,
                       @RequestParam(value = "page", defaultValue = "1") int page,
                       @RequestParam(value = "pageSize", defaultValue = "10") int pageSize) {
        if (page <= 0) {
            page = 1;
        }
        if (pageSize <= 0) {
            pageSize = 10;
        }
        if (pageSize > 100) {
            pageSize = 100;
        }

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> params = new ArrayList<Object>();
        if (keyword != null && keyword.trim().length() > 0) {
            where.append(" AND (name LIKE ? OR remark LIKE ?)");
            params.add("%" + keyword.trim() + "%");
            params.add("%" + keyword.trim() + "%");
        }
        if (categoryId != null && categoryId.trim().length() > 0 && !"0".equals(categoryId.trim())) {
            where.append(" AND category_id = ?");
            params.add(Long.valueOf(Long.parseLong(categoryId.trim())));
        }

        long total = 0L;
        List<Dict> cnt = holder.executeQuery("SELECT COUNT(*) AS c FROM app" + where, params.toArray());
        if (!cnt.isEmpty() && cnt.get(0).get("c") != null) {
            total = ((Number) cnt.get(0).get("c")).longValue();
        }

        String sql = "SELECT id, category_id, name, token, source_type, file_paths, cmd, remark, create_time, update_time"
                + " FROM app" + where + " ORDER BY id DESC LIMIT ? OFFSET ?";
        int limit = pageSize;
        int offset = (page - 1) * pageSize;
        List<Object> pageParams = new ArrayList<Object>(params);
        pageParams.add(Integer.valueOf(limit));
        pageParams.add(Integer.valueOf(offset));
        List<Dict> rows = holder.executeQuery(sql, pageParams.toArray());

        Dict resp = new Dict();
        resp.put("list", rows);
        resp.put("total", Long.valueOf(total));
        resp.put("page", Integer.valueOf(page));
        resp.put("pageSize", Integer.valueOf(pageSize));
        return ApiResponse.ok(resp);
    }

    /** 下拉用的精简列表：只带 id / name，给筛选框与表单使用 */
    @GetMapping("/api/apps/options")
    public Object options() {
        List<Dict> rows = holder.executeQuery("SELECT id, name FROM app ORDER BY id DESC");
        return ApiResponse.ok(rows);
    }

    @PostMapping("/api/apps")
    public Object create(@RequestBody Dict body) {
        String name = (String) body.get("name");
        if (name == null || name.length() == 0) {
            Dict d = new Dict();
            d.put("error", "name required");
            return ApiResponse.ok(d);
        }
        Object cid = body.get("categoryId");
        long categoryId = cid instanceof Number ? ((Number) cid).longValue() : 0L;
        String sourceType = (String) body.get("sourceType");
        if (sourceType == null) {
            sourceType = "HTTP";
        }
        String filePaths = (String) body.get("filePaths");
        String cmd = (String) body.get("cmd");
        String remark = (String) body.get("remark");
        String token = UUID.randomUUID().toString().replace("-", "");

        Dict row = new Dict();
        row.put("category_id", categoryId);
        row.put("name", name);
        row.put("token", token);
        row.put("source_type", sourceType);
        row.put("file_paths", filePaths);
        row.put("cmd", cmd);
        row.put("remark", remark);
        row.put("create_time", new Date());
        row.put("update_time", new Date());
        holder.insert("app", row);

        long newId = 0L;
        List<Dict> idRows = holder.executeQuery("SELECT MAX(id) AS id FROM app");
        if (!idRows.isEmpty() && idRows.get(0).get("id") != null) {
            newId = ((Number) idRows.get(0).get("id")).longValue();
        }
        appService.refresh();

        Dict r = new Dict();
        r.put("id", newId);
        r.put("token", token);
        return ApiResponse.ok(r);
    }

    @PutMapping("/api/apps/{id}")
    public Object update(@PathVariable("id") long id, @RequestBody Dict body) {
        StringBuilder sql = new StringBuilder("UPDATE app SET update_time = ?");
        List<Object> params = new ArrayList<Object>();
        params.add(new Date());
        if (body.containsKey("name")) {
            sql.append(", name = ?");
            params.add(body.get("name"));
        }
        if (body.containsKey("categoryId")) {
            sql.append(", category_id = ?");
            params.add(((Number) body.get("categoryId")).longValue());
        }
        if (body.containsKey("sourceType")) {
            sql.append(", source_type = ?");
            params.add(body.get("sourceType"));
        }
        if (body.containsKey("filePaths")) {
            sql.append(", file_paths = ?");
            params.add(body.get("filePaths"));
        }
        if (body.containsKey("cmd")) {
            sql.append(", cmd = ?");
            params.add(body.get("cmd"));
        }
        if (body.containsKey("remark")) {
            sql.append(", remark = ?");
            params.add(body.get("remark"));
        }
        sql.append(" WHERE id = ?");
        params.add(id);
        holder.executeUpdate(sql.toString(), params);
        appService.refresh();

        Dict d = new Dict();
        d.put("updated", id);
        return ApiResponse.ok(d);
    }

    @DeleteMapping("/api/apps/{id}")
    public Object delete(@PathVariable("id") long id) {
        holder.executeUpdate("DELETE FROM app WHERE id = ?", id);
        appService.refresh();
        Dict d = new Dict();
        d.put("deleted", id);
        return ApiResponse.ok(d);
    }
}
