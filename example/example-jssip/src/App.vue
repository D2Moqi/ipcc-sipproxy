<template>
  <div class="app-container">
    <div class="app-header">
      <h1 class="app-title">example-jssip SIP 注册测试</h1>
    </div>
    <SoftPhone />
  </div>
</template>

<script setup lang="ts">
import SoftPhone from './SoftPhone.vue'
</script>

<style>
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

html,
body,
#app {
  width: 100%;
  height: 100%;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial,
    'PingFang SC', 'Microsoft YaHei', sans-serif;
  background: #f5f7fa;
  color: #303133;
}

.app-container {
  width: 100%;
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}

.app-header {
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
  padding: 16px 24px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.04);
}

.app-title {
  font-size: 20px;
  font-weight: 600;
  color: #303133;
  margin: 0;
}
</style>
