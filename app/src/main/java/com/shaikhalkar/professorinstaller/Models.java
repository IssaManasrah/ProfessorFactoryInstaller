package com.shaikhalkar.professorinstaller;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class Models {
    private Models() {}

    static final class AppInfo {
        String id = "";
        String name = "";
        String description = "";
        String packageName = "";
        String fileName = "";
        String downloadUrl = "";
        String sha256 = "";
        String country = "";
        String versionName = "";
        long versionCode = 1;
        boolean required;
        final List<String> allPackageNames = new ArrayList<>();

        static AppInfo fromJson(JSONObject o) {
            AppInfo a = new AppInfo();
            if (o == null) return a;
            a.id = o.optString("id", "");
            a.name = o.optString("name", a.id);
            a.description = o.optString("description", "");
            a.packageName = o.optString("packageName", "");
            a.fileName = o.optString("fileName", "");
            a.downloadUrl = o.optString("downloadUrl", "");
            a.sha256 = o.optString("sha256", "").toLowerCase();
            a.country = o.optString("country", "");
            a.versionName = o.optString("versionName", "");
            a.versionCode = o.optLong("versionCode", 1);
            a.required = o.optBoolean("required", false);
            JSONArray packages = o.optJSONArray("allPackageNames");
            if (packages != null) {
                for (int i = 0; i < packages.length(); i++) {
                    String p = packages.optString(i, "");
                    if (!p.isEmpty() && !a.allPackageNames.contains(p)) a.allPackageNames.add(p);
                }
            }
            if (!a.packageName.isEmpty() && !a.allPackageNames.contains(a.packageName)) {
                a.allPackageNames.add(a.packageName);
            }
            return a;
        }

        String queueKey() {
            return id + "|" + packageName + "|" + versionCode;
        }
    }

    static final class GroupInfo {
        String id = "";
        String name = "";
        final List<String> appIds = new ArrayList<>();

        static GroupInfo fromJson(JSONObject o) {
            GroupInfo g = new GroupInfo();
            if (o == null) return g;
            g.id = o.optString("id", "");
            g.name = o.optString("name", g.id);
            JSONArray ids = o.optJSONArray("appIds");
            if (ids != null) {
                for (int i = 0; i < ids.length(); i++) {
                    String id = ids.optString(i, "");
                    if (!id.isEmpty() && !g.appIds.contains(id)) g.appIds.add(id);
                }
            }
            return g;
        }
    }

    static final class Catalog {
        String country = "JO";
        final List<AppInfo> apps = new ArrayList<>();
        final List<GroupInfo> groups = new ArrayList<>();

        static Catalog fromJson(JSONObject root) {
            Catalog c = new Catalog();
            c.country = root.optString("country", "JO");
            JSONArray apps = root.optJSONArray("apps");
            if (apps != null) {
                for (int i = 0; i < apps.length(); i++) c.apps.add(AppInfo.fromJson(apps.optJSONObject(i)));
            }
            JSONArray groups = root.optJSONArray("groups");
            if (groups != null) {
                for (int i = 0; i < groups.length(); i++) c.groups.add(GroupInfo.fromJson(groups.optJSONObject(i)));
            }
            return c;
        }

        AppInfo findApp(String id) {
            for (AppInfo a : apps) if (a.id.equals(id)) return a;
            return null;
        }
    }

    static final class SupportOperation {
        String action = "install";
        AppInfo app;
        final List<String> removePackages = new ArrayList<>();
        int step;

        static SupportOperation fromJson(JSONObject o) {
            SupportOperation op = new SupportOperation();
            op.action = o.optString("action", "install");
            op.app = AppInfo.fromJson(o.optJSONObject("app"));
            op.step = o.optInt("step", 0);
            JSONArray rp = o.optJSONArray("removePackages");
            if (rp != null) {
                for (int i = 0; i < rp.length(); i++) {
                    String p = rp.optString(i, "");
                    if (!p.isEmpty() && !op.removePackages.contains(p)) op.removePackages.add(p);
                }
            }
            return op;
        }
    }

    static final class SupportJob {
        String code = "";
        String country = "JO";
        String message = "مهمة دعم فني";
        final List<SupportOperation> operations = new ArrayList<>();

        static SupportJob fromJson(JSONObject root) {
            SupportJob j = new SupportJob();
            j.code = root.optString("code", "");
            j.country = root.optString("country", "JO");
            j.message = root.optString("message", "مهمة دعم فني");
            JSONArray ops = root.optJSONArray("operations");
            if (ops != null) {
                for (int i = 0; i < ops.length(); i++) {
                    JSONObject o = ops.optJSONObject(i);
                    if (o != null) j.operations.add(SupportOperation.fromJson(o));
                }
            }
            return j;
        }
    }
}
