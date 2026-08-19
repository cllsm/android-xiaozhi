<!--
 * @Date: 2025-04-07 15:13:07
 * @LastEditTimes: Do not edit
 * @Descripttion: describe
-->
<template>
  <view
    class="circle-menu"
    :class="{ 'circle-menu-active': isOpen }"
    @click="closeFab">
    <view
      v-for="(item, index) in menuItems"
      :key="index"
      class="menu-item"
      :class="{ 'menu-item-active': isOpen }"
      :style="newItemStyle[index]"
      @click.stop="handleSelect(item)">
      <slot name="menu-item" :data="item" :index="index">
        <text class="menu-icon">{{ item.icon }}</text>
      </slot>
    </view>
  </view>
</template>

<script setup>
import { defineProps, defineEmits, ref, watchEffect } from "vue";
import { circlePositionConfig } from "./fab.config";

const props = defineProps({
  menuItems: {
    type: Array,
    required: true,
  },
  isOpen: {
    type: Boolean,
    default: true,
  },
  position: {
    type: Array,
    default: () => [10, 100],
  },
});
const emit = defineEmits(["select", "close"]);
const direction = ref("left");
const closeFab = () => {
  emit("close");
};
const getItemStyle = (index) => {
  const config = circlePositionConfig[direction.value];

  const { x, y, delay } = config[index] || config[0];

  return {
    "--x": `${x}rpx`,
    "--y": `${y}rpx`,
    transitionDelay: `${delay}s`,
  };
};

const newItemStyle = ref({
  0: getItemStyle(0),
  1: getItemStyle(1),
  2: getItemStyle(2),
  3: getItemStyle(3),
});

watchEffect(() => {
  if (props.isOpen) {
    const [x] = props.position;
    // 获取页面宽度
    const windowWidth = uni.getWindowInfo().windowWidth;
    if (windowWidth / 2 > x) {
      direction.value = "right";
    } else {
      direction.value = "left";
    }
    newItemStyle.value = {
      0: getItemStyle(0),
      1: getItemStyle(1),
      2: getItemStyle(2),
      3: getItemStyle(3),
    };
  }
});

const handleSelect = (item) => {
  console.log(item, "item");
  emit("select", item);
};
</script>

<style lang="scss" scoped>
.circle-menu {
  position: absolute;
  top: 50%;
  left: 50%;
  width: 100px;
  height: 200px;
  pointer-events: none;
  opacity: 0;
  transition: all 0.3s ease;
  transform: translate(-50%, -50%);

  &.circle-menu-active {
    pointer-events: auto;
    opacity: 1;
  }
}

.menu-item {
  position: absolute;
  // left: 0;
  // top: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 35px;
  height: 35px;
  background-color: #ffffff;
  border-radius: 50%;
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.15);
  opacity: 0;
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
  transform: scale(0.5) translate(0);
  transform-origin: center center;

  &.menu-item-active {
    opacity: 1;
    transform: scale(1) translate(var(--x, 0), var(--y, 0));
  }
}

.menu-icon {
  font-size: 18px;
}
</style>
