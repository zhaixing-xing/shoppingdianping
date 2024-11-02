package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    private final StringRedisTemplate stringRedisTemplate;
    public BlogServiceImpl(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }
    @Override
    public Result likeBlog(Long id) {
        //1.获取登录用户
        UserDTO user = UserHolder.getUser();
        Long userId = user.getId();
        //2.判断当前用户是否已经点赞过
        String key = "blog:liked:" +id;
        Boolean isMember = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
        if(BooleanUtil.isFalse(isMember)){
            //3.未点赞，可以点赞
            //3.1.数据库点赞数+1
            boolean isSuccess = update().setSql("liked=liked+1").eq("id", id).update();
            //3.2.保存用户到Redis
            if(isSuccess){
                stringRedisTemplate.opsForSet().add(key,userId.toString());
            }
        }else{
            //4.已点赞，取消点赞
            //4.1.数据库点赞数-1
            boolean isSuccess = update().setSql("liked=liked-1").eq("id", id).update();
            //4.2.把用户从Redis的set集合移除
            stringRedisTemplate.opsForSet().remove(key,userId.toString());
        }
        return Result.ok();

    }
}
