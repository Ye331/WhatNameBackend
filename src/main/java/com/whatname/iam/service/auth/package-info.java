/**
 * auth功能的service层。PasswordHasher/PasswordPolicy是A内部密码基础能力，尚未接入HTTP或持久化。
 * scrypt存储采用Spring原生$110801$saltBase64$keyBase64，N=131072/r=8/p=1，随机盐16字节、派生值32字节。
 * 当前仅支持此参数组合，不是已经与D确认的共享格式；未来兼容其他编码须显式评估并增加有界支持。
 */
package com.whatname.iam.service.auth;
