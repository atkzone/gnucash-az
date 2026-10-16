# -*- coding: utf-8 -*-
"""R25 新增字符串（values + values-zh-rCN）。"""
import sys

sys.path.insert(0, ".")
from _r25_apply import patch  # noqa: E402

EN = "app/src/main/res/values/strings.xml"
ZH = "app/src/main/res/values-zh-rCN/strings.xml"

EN_ANCHOR = '''    <string name="label_extended_view_field_count">Fields: %1$d</string>
'''
EN_NEW = '''    <string name="label_extended_view_field_count">Fields: %1$d</string>
    <string name="extended_view_none">No extended view</string>
    <string name="label_extended_display_fields">Fields to show</string>
    <string name="hint_extended_display_fields">Pick a view above, then tick the fields it should add</string>
    <string name="toast_extended_view_selected">Using extended view: %1$s</string>
    <string name="toast_extended_view_disabled">Extended display turned off</string>
    <string name="bulk_edit_running">Updating %1$d transactions…</string>
    <string name="menu_report_search_results">Report</string>
    <string name="title_search_report">Search results</string>
    <string name="label_search_report_empty">Nothing to report for this search</string>
    <string name="label_search_report_total">Total</string>
    <string name="label_search_report_date">Date</string>
    <string name="label_search_report_description">Description</string>
    <string name="label_search_report_account">Account</string>
    <string name="label_search_report_amount">Amount</string>
    <string name="btn_manual_date">Enter manually</string>
    <string name="title_manual_date">Enter date</string>
    <string name="hint_manual_date">20260129</string>
    <string name="toast_manual_date_invalid">Not a valid date — type it as 20260129</string>
'''

ZH_ANCHOR = '''    <string name="label_extended_view_field_count">字段：%1$d</string>
'''
ZH_NEW = '''    <string name="label_extended_view_field_count">字段：%1$d</string>
    <string name="extended_view_none">不使用扩展显示</string>
    <string name="label_extended_display_fields">显示的字段</string>
    <string name="hint_extended_display_fields">先在上方选中一个扩展视图，再勾选它要加显示的字段</string>
    <string name="toast_extended_view_selected">已使用扩展视图：%1$s</string>
    <string name="toast_extended_view_disabled">已关闭扩展显示</string>
    <string name="bulk_edit_running">正在更新 %1$d 笔交易…</string>
    <string name="menu_report_search_results">报表</string>
    <string name="title_search_report">搜索结果</string>
    <string name="label_search_report_empty">本次搜索没有可显示的报表数据</string>
    <string name="label_search_report_total">合计</string>
    <string name="label_search_report_date">日期</string>
    <string name="label_search_report_description">描述</string>
    <string name="label_search_report_account">账户</string>
    <string name="label_search_report_amount">金额</string>
    <string name="btn_manual_date">手工填写</string>
    <string name="title_manual_date">手工填写日期</string>
    <string name="hint_manual_date">20260129</string>
    <string name="toast_manual_date_invalid">日期格式不对，请按 20260129 输入</string>
'''

patch(EN, [(EN_ANCHOR, EN_NEW)])
patch(ZH, [(ZH_ANCHOR, ZH_NEW)])
print("R25 strings done")
