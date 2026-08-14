package com.shop.order.model;

public class Order {

    private String bizNo;
    private long amount;
    private long paidAmount;
    private String status;
    private String remark;

    public Order(String bizNo, long amount, String status) {
        this.bizNo = bizNo;
        this.amount = amount;
        this.status = status;
    }

    public String getBizNo() {
        return bizNo;
    }

    public long getAmount() {
        return amount;
    }

    public long getPaidAmount() {
        return paidAmount;
    }

    public void setPaidAmount(long paidAmount) {
        this.paidAmount = paidAmount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
