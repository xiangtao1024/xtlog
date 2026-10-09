package top.xtcoder.xtlog.controller;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import top.xtcoder.xtboot.context.annotation.Autowired;
import top.xtcoder.xtboot.core.Dict;
import top.xtcoder.xtboot.orm.DbClientHolder;
import top.xtcoder.xtboot.web.annotation.DeleteMapping;
import top.xtcoder.xtboot.web.annotation.GetMapping;
import top.xtcoder.xtboot.web.annotation.PathVariable;
import top.xtcoder.xtboot.web.annotation.PostMapping;
import top.xtcoder.xtboot.web.annotation.PutMapping;
import top.xtcoder.xtboot.web.annotation.RequestBody;
import top.xtcoder.xtboot.web.annotation.RestController;
import top.xtcoder.xtboot.auth.annotation.SaCheckLogin;
import top.xtcoder.xtboot.web.model.ApiResponse;

/**
 * 分类管理：日志按业务域归类，应用挂在分类下。
 */
@RestController
@SaCheckLogin
public class CategoryController {

    @Autowired
    private DbClientHolder holder;

    @GetMapping("/api/categories")
    public Object list() {
        List<Dict> rows = holder.executeQuery(
                "SELECT id, name, remark, create_time, update_time FROM category ORDER BY id DESC");
        return ApiResponse.ok(rows);
    }

    @PostMapping("/api/categories")
    public Object create(@RequestBody Dict body) {
        String name = (String) body.get("name");
        if (name == null || name.length() == 0) {
            Dict d = new Dict();
            d.put("error", "name required");
            return ApiResponse.ok(d);
        }
        Dict row = new Dict();
        row.put("name", name);
        row.put("remark", body.get("remark"));
        row.put("create_time", new Date());
        row.put("update_time", new Date());
        holder.insert("category", row);

        long newId = 0L;
        List<Dict> idRows = holder.executeQuery("SELECT MAX(id) AS id FROM category");
        if (!idRows.isEmpty() && idRows.get(0).get("id") != null) {
            newId = ((Number) idRows.get(0).get("id")).longValue();
        }
        Dict r = new Dict();
        r.put("id", newId);
        return ApiResponse.ok(r);
    }

    @PutMapping("/api/categories/{id}")
    public Object update(@PathVariable("id") long id, @RequestBody Dict body) {
        StringBuilder sql = new StringBuilder("UPDATE category SET update_time = ?");
        List<Object> params = new ArrayList<Object>();
        params.add(new Date());
        if (body.containsKey("name")) {
            sql.append(", name = ?");
            params.add(body.get("name"));
        }
        if (body.containsKey("remark")) {
            sql.append(", remark = ?");
            params.add(body.get("remark"));
        }
        sql.append(" WHERE id = ?");
        params.add(id);
        holder.executeUpdate(sql.toString(), params);

        Dict d = new Dict();
        d.put("updated", id);
        return ApiResponse.ok(d);
    }

    @DeleteMapping("/api/categories/{id}")
    public Object delete(@PathVariable("id") long id) {
        holder.executeUpdate("DELETE FROM category WHERE id = ?", id);
        Dict d = new Dict();
        d.put("deleted", id);
        return ApiResponse.ok(d);
    }
}
